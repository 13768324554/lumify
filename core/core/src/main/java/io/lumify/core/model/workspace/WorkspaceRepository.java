package io.lumify.core.model.workspace;

import io.lumify.core.exception.LumifyAccessDeniedException;
import io.lumify.core.model.workspace.diff.DiffItem;
import io.lumify.core.security.LumifyVisibility;
import io.lumify.core.user.User;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.securegraph.util.ConvertingIterable;

import java.util.List;

import static com.google.common.base.Preconditions.checkNotNull;

public abstract class WorkspaceRepository {
    public static final String VISIBILITY_STRING = "workspace";
    public static final LumifyVisibility VISIBILITY = new LumifyVisibility(VISIBILITY_STRING);
    public static final String WORKSPACE_CONCEPT_NAME = "http://lumify.io/workspace";
    public static final String WORKSPACE_TO_ENTITY_RELATIONSHIP_NAME = "http://lumify.io/workspace/toEntity";
    public static final String WORKSPACE_TO_USER_RELATIONSHIP_NAME = "http://lumify.io/workspace/toUser";
    public static final String WORKSPACE_ID_PREFIX = "WORKSPACE_";

    public abstract void delete(Workspace workspace, User user);

    public abstract Workspace findById(String workspaceId, User user);

    public Iterable<Workspace> findByIds(final Iterable<String> workspaceIds, final User user) {
        return new ConvertingIterable<String, Workspace>(workspaceIds) {
            @Override
            protected Workspace convert(String workspaceId) {
                if (workspaceId == null) {
                    return null;
                }
                try {
                    return findById(workspaceId, user);
                } catch (LumifyAccessDeniedException ex) {
                    return null;
                }
            }
        };
    }

    public abstract Workspace add(String title, User user);

    public abstract Iterable<Workspace> findAll(User user);

    public abstract void setTitle(Workspace workspace, String title, User user);

    public abstract List<WorkspaceUser> findUsersWithAccess(String workspaceId, User user);

    public abstract List<WorkspaceEntity> findEntities(Workspace workspace, User user);

    public Workspace copy(Workspace workspace, User user) {
        return copyTo(workspace, user, user);
    }

    public Workspace copyTo(Workspace workspace, User destinationUser, User user) {
        Workspace newWorkspace = add("Copy of " + workspace.getDisplayTitle(), destinationUser);
        List<WorkspaceEntity> entities = findEntities(workspace, user);
        for (WorkspaceEntity entity : entities) {
            updateEntityOnWorkspace(newWorkspace, entity.getEntityVertexId(), entity.isVisible(), entity.getGraphPositionX(), entity.getGraphPositionY(), destinationUser);
        }
        return newWorkspace;
    }

    public abstract void softDeleteEntityFromWorkspace(Workspace workspace, String vertexId, User user);

    public abstract void updateEntityOnWorkspace(Workspace workspace, String vertexId, Boolean visible, Integer graphPositionX, Integer graphPositionY, User user);

    public abstract void deleteUserFromWorkspace(Workspace workspace, String userId, User user);

    public abstract void updateUserOnWorkspace(Workspace workspace, String userId, WorkspaceAccess workspaceAccess, User user);

    public abstract List<DiffItem> getDiff(Workspace workspace, User user);

    public String getCreatorUserId(Workspace workspace, User user) {
        for (WorkspaceUser workspaceUser : findUsersWithAccess(workspace.getWorkspaceId(), user)) {
            if (workspaceUser.isCreator()) {
                return workspaceUser.getUserId();
            }
        }
        return null;
    }

    public abstract boolean hasWritePermissions(String workspaceId, User user);

    public abstract boolean hasReadPermissions(String workspaceId, User user);

    public JSONArray toJson(Iterable<Workspace> workspaces, User user, boolean includeVertices) {
        JSONArray resultJson = new JSONArray();
        for (Workspace workspace : workspaces) {
            resultJson.put(toJson(workspace, user, includeVertices));
        }
        return resultJson;
    }

    public JSONObject toJson(Workspace workspace, User user, boolean includeVertices) {
        checkNotNull(workspace, "workspace cannot be null");
        checkNotNull(user, "user cannot be null");

        try {
            JSONObject workspaceJson = new JSONObject();
            workspaceJson.put("workspaceId", workspace.getWorkspaceId());
            workspaceJson.put("title", workspace.getDisplayTitle());

            String creatorUserId = getCreatorUserId(workspace, user);
            if (creatorUserId != null) {
                workspaceJson.put("createdBy", creatorUserId);
                workspaceJson.put("isSharedToUser", !creatorUserId.equals(user.getUserId()));
            }
            workspaceJson.put("isEditable", hasWritePermissions(workspace.getWorkspaceId(), user));

            JSONArray usersJson = new JSONArray();
            for (WorkspaceUser workspaceUser : findUsersWithAccess(workspace.getWorkspaceId(), user)) {
                String userId = workspaceUser.getUserId();
                JSONObject userJson = new JSONObject();
                userJson.put("userId", userId);
                userJson.put("access", workspaceUser.getWorkspaceAccess().toString().toLowerCase());
                usersJson.put(userJson);
            }
            workspaceJson.put("users", usersJson);

            if (includeVertices) {
                JSONArray verticesJson = new JSONArray();
                for (WorkspaceEntity workspaceEntity : findEntities(workspace, user)) {
                    if (!workspaceEntity.isVisible()) {
                        continue;
                    }

                    JSONObject vertexJson = new JSONObject();
                    vertexJson.put("vertexId", workspaceEntity.getEntityVertexId());

                    Integer graphPositionX = workspaceEntity.getGraphPositionX();
                    Integer graphPositionY = workspaceEntity.getGraphPositionY();
                    if (graphPositionX != null && graphPositionY != null) {
                        JSONObject graphPositionJson = new JSONObject();
                        graphPositionJson.put("x", graphPositionX);
                        graphPositionJson.put("y", graphPositionY);
                        vertexJson.put("graphPosition", graphPositionJson);
                    }

                    verticesJson.put(vertexJson);
                }
                workspaceJson.put("vertices", verticesJson);
            }

            return workspaceJson;
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }
}

