package ai.verta.modeldb.experimentRun.subtypes;

import ai.verta.modeldb.CodeVersion;
import ai.verta.modeldb.GitSnapshot;
import ai.verta.modeldb.Location;
import ai.verta.modeldb.common.CommonUtils;
import ai.verta.modeldb.common.exceptions.InternalErrorException;
import ai.verta.modeldb.common.futures.FutureJdbi;
import ai.verta.modeldb.common.futures.InternalFuture;
import ai.verta.modeldb.entities.code.GitCodeBlobEntity;
import ai.verta.modeldb.entities.code.NotebookCodeBlobEntity;
import ai.verta.modeldb.entities.dataset.PathDatasetComponentBlobEntity;
import ai.verta.modeldb.utils.ModelDBHibernateUtil;
import ai.verta.modeldb.utils.ModelDBUtils;
import ai.verta.modeldb.versioning.Blob;
import ai.verta.modeldb.versioning.GitCodeBlob;
import ai.verta.modeldb.versioning.PathDatasetComponentBlob;
import com.google.protobuf.InvalidProtocolBufferException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.Session;
import org.hibernate.query.Query;

public class CodeVersionFromBlobHandler {
  private static Logger LOGGER = LogManager.getLogger(CodeVersionFromBlobHandler.class);
  private final FutureJdbi jdbi;
  private final Executor executor;
  private final boolean populateConnectionsBasedOnPrivileges;
  private final ModelDBHibernateUtil modelDBHibernateUtil = ModelDBHibernateUtil.getInstance();

  public CodeVersionFromBlobHandler(
      Executor executor, FutureJdbi jdbi, boolean populateConnectionsBasedOnPrivileges) {
    this.executor = executor;
    this.jdbi = jdbi;
    this.populateConnectionsBasedOnPrivileges = populateConnectionsBasedOnPrivileges;
  }

  /**
   * @param expRunIds : ExperimentRun ids
   * @return {@link Map <String, Map<String, CodeBlob >>} : Map from experimentRunID to Map of
   *     LocationString to CodeVersion
   */
  public InternalFuture<Map<String, Map<String, CodeVersion>>> getExperimentRunCodeVersionMap(
      Set<String> expRunIds, List<String> selfAllowedRepositoryIds) {

    List<Object[]> codeBlobEntities;
    try (Session session = modelDBHibernateUtil.getSessionFactory().openSession()) {
      String queryBuilder =
          "SELECT vme.experimentRunEntity.id, vme.versioning_location, gcb, ncb, pdcb "
              + " From VersioningModeldbEntityMapping vme LEFT JOIN GitCodeBlobEntity gcb ON vme.blob_hash = gcb.blob_hash "
              + " LEFT JOIN NotebookCodeBlobEntity ncb ON vme.blob_hash = ncb.blob_hash "
              + " LEFT JOIN PathDatasetComponentBlobEntity pdcb ON ncb.path_dataset_blob_hash = pdcb.id.path_dataset_blob_id "
              + " WHERE vme.versioning_blob_type = :versioningBlobType AND vme.experimentRunEntity.id IN (:expRunIds) ";

      if (populateConnectionsBasedOnPrivileges) {
        if (selfAllowedRepositoryIds == null || selfAllowedRepositoryIds.isEmpty()) {
          return InternalFuture.completedInternalFuture(new HashMap<>());
        } else {
          queryBuilder = queryBuilder + " AND vme.repository_id IN (:repoIds)";
        }
      }

      Query query = session.createQuery(queryBuilder);
      query.setParameter("versioningBlobType", Blob.ContentCase.CODE.getNumber());
      query.setParameterList("expRunIds", expRunIds);
      if (populateConnectionsBasedOnPrivileges) {
        query.setParameterList(
            "repoIds",
            selfAllowedRepositoryIds.stream().map(Long::parseLong).collect(Collectors.toList()));
      }

      LOGGER.debug(
          "Final experimentRuns code config blob final query : {}", query.getQueryString());
      codeBlobEntities = query.list();
      LOGGER.debug("Final experimentRuns code config list size : {}", codeBlobEntities.size());
    }

    // Map<experimentRunID, Map<LocationString, CodeVersion>> : Map from experimentRunID to Map of
    // LocationString to CodeVersion
    Map<String, Map<String, CodeVersion>> expRunCodeBlobMap = new LinkedHashMap<>();
    if (!codeBlobEntities.isEmpty()) {
      for (Object[] objects : codeBlobEntities) {
        String expRunId = (String) objects[0];
        String versioningLocation = (String) objects[1];
        GitCodeBlobEntity gitBlobEntity = (GitCodeBlobEntity) objects[2];
        NotebookCodeBlobEntity notebookCodeBlobEntity = (NotebookCodeBlobEntity) objects[3];
        PathDatasetComponentBlobEntity pathDatasetComponentBlobEntity =
            (PathDatasetComponentBlobEntity) objects[4];

        CodeVersion.Builder codeVersionBuilder = CodeVersion.newBuilder();
        LOGGER.debug("notebookCodeBlobEntity {}", notebookCodeBlobEntity);
        LOGGER.debug("pathDatasetComponentBlobEntity {}", pathDatasetComponentBlobEntity);
        LOGGER.debug("gitBlobEntity {}", gitBlobEntity);
        if (notebookCodeBlobEntity != null) {
          if (pathDatasetComponentBlobEntity != null) {
            convertGitBlobToGitSnapshot(
                codeVersionBuilder,
                notebookCodeBlobEntity.getGitCodeBlobEntity().toProto(),
                pathDatasetComponentBlobEntity.toProto());
          } else {
            convertGitBlobToGitSnapshot(
                codeVersionBuilder, notebookCodeBlobEntity.getGitCodeBlobEntity().toProto(), null);
          }
        } else if (gitBlobEntity != null) {
          convertGitBlobToGitSnapshot(codeVersionBuilder, gitBlobEntity.toProto(), null);
        }
        Map<String, CodeVersion> codeBlobMap = expRunCodeBlobMap.get(expRunId);
        if (codeBlobMap == null) {
          codeBlobMap = new LinkedHashMap<>();
        }
        Location.Builder locationBuilder = Location.newBuilder();
        try {
          CommonUtils.getProtoObjectFromString(versioningLocation, locationBuilder);
        } catch (InvalidProtocolBufferException ex) {
          throw new InternalErrorException(
              "Error getting while converting versioning location:" + ex.getMessage());
        }
        codeBlobMap.put(
            ModelDBUtils.getLocationWithSlashOperator(locationBuilder.getLocationList()),
            codeVersionBuilder.build());
        expRunCodeBlobMap.put(expRunId, codeBlobMap);
      }
    }
    return InternalFuture.completedInternalFuture(expRunCodeBlobMap);
  }

  private void convertGitBlobToGitSnapshot(
      CodeVersion.Builder codeVersionBuilder,
      GitCodeBlob codeBlob,
      PathDatasetComponentBlob pathComponentBlob) {
    GitSnapshot.Builder gitSnapShot = GitSnapshot.newBuilder();
    if (codeBlob != null) {
      gitSnapShot
          .setRepo(codeBlob.getRepo())
          .setHash(codeBlob.getHash())
          .setIsDirtyValue(codeBlob.getIsDirty() ? 1 : 2)
          .build();
    }
    if (pathComponentBlob != null) {
      gitSnapShot.addFilepaths(pathComponentBlob.getPath());
    }
    codeVersionBuilder.setGitSnapshot(gitSnapShot);
  }
}
