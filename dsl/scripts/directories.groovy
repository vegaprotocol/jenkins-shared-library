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
        id: 'private/Deployments/Vegacapsule',
        display: 'Vegacapsule',
    ],
    [
        id: 'private/Deployments/Veganet',
        display: 'Veganet'
    ],
    [
        id: 'private/playgrounds',
        display: 'Playgrounds',
    ],
    [
        id: 'private/Snapshots',
        dispaly: 'Snapshots',
    ],
    [
        id: 'common',
        display: 'Common',
    ]
]

dirs.each { directory ->
    folder(directory.id){
        displayName(directory.display)
        if (directory.description) {
            description(directory.description)
        }
    }
}
