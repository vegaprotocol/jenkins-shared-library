def dirs = [
    [
        id: 'private',
        display: '[Private]',
    ],
    [
        id: 'private/Automations',
        display: 'Automations',
    ],
    [
        id :'private/Deployments',
        display: 'Deployments',
    ],
    [
        id: 'private/Deployments/devnet1',
        display: 'Devnet 1',
    ],
    [
        id: 'private/Deployments/fairground',
        display: 'Fairground',
    ],
    [
        id: 'private/Deployments/stagnet1',
        display: 'Stagnet 1',
    ],
    [
        id: 'private/Deployments/stagnet2',
        display: 'Stagnet 2',
    ],
    [
        id: 'private/Deployments/stagnet3',
        display: 'Stagnet 3',
    ],
    [
        id: 'private/Deployments/Vegacapsule',
        display: 'Vegacapsule',
    ],
    [
        id: 'private/Deployments/Veganet',
        display: 'Veganet',
    ],
    [
        id: 'private/Deployments/sandbox',
        display: 'Sandbox',
    ],
    [
        id: 'private/playgrounds',
        display: 'Playgrounds',
    ],
    [
        id: 'private/Snapshots',
        display: 'Snapshots',
    ],
    [
        id: 'common',
        display: 'Common',
        permissions: [
            'anonymous': [
                'hudson.model.View.Read',
                'hudson.model.Item.Read',
            ],
        ],
    ],
]

dirs.each { directory ->
    folder(directory.id){
        if (directory.display) {
            displayName(directory.display)
        }
        if (directory.description) {
            description(directory.description)
        }
        if (directory.permissions) {
            authorization {
                directory.permissions.each { user, userPermissions ->
                    permissions(user, userPermissions)
                }
            }
        }
    }
}
