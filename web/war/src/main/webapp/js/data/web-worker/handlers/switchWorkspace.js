define(['../services/workspace'], function(Workspace) {
    'use strict';

    return function(message) {
        Promise.all([
            Workspace.edges(message.workspaceId),
            Promise.all([
                Workspace.get(message.workspaceId),
                Workspace.vertices(message.workspaceId),
                Promise.require('data/web-worker/util/store')
            ]).then(function(results) {
                var workspace = results.shift(),
                    vertices = results.shift().vertices,
                    store = results.shift();

                pushSocketMessage({
                    type: 'setActiveWorkspace',
                    data: {
                        workspaceId: workspace.workspaceId,
                        userId: publicData.currentUser.id
                    }
                });
                dispatchMain('workspaceLoaded', {
                    workspace: workspace,
                    vertices: vertices
                });
                store.setVerticesInWorkspace(
                    workspace.workspaceId,
                    _.pluck(workspace.vertices, 'vertexId')
                );
            })
        ]).done(function(result) {
            dispatchMain('edgesLoaded', {
                edges: result[0]
            })
        })
    };

});
