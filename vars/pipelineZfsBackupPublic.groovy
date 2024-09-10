void call() {
    Boolean dryRun = params.DRY_RUN ?: false
    Boolean enableCreateZfsSnapshot = params.ENABLE_CREATE_ZFS_SNAPSHOT ?: false
    Boolean enableRollbackZfsSnapshot = params.ENABLE_ROLLBACK_ZFS_SNAPSHOT ?: false
    Boolean enableDestroyZfsSnapshot = params.ENABLE_DESTROY_ZFS_SNAPSHOT ?: false

    String createZfsSnapshotName = params.CREATE_ZFS_SNAPSHOT_NAME ?: ''
    String rollbackZfsSnapshotName = params.ROLLBACK_ZFS_SNAPSHOT_NAME ?: ''
    String destroyZfsSnapshotsNames = params.DESTROY_ZFS_SNAPSHOT_NAMES ?: ''
    String nodeLimit = params.NODE ?: ''

    String ansibleLimit = ""

    String environmentName = env.NET_NAME ?: ''

    String ansibleTestnetAutomationBranch = params.ANSIBLE_TEST_AUTOMATION_BRANCH ?: 'main'
    String networksConfigPrivateBranch = params.NETWORKS_CONFIG_PRIVATE_BRANCH ?: 'main'

    pipeline {
        agent {
            label params.NODE_LABEL
        }
        // agent any
        options {
            ansiColor('xterm')
            skipDefaultCheckout()
            timestamps()
            timeout(time: 35, unit: 'MINUTES')
        }
        // environment {
        // }
        stages {
            stage('CI config') {
                steps {
                    script {
                        List<String> description = []
                        
                        vegautils.commonCleanup()
                        description << "node: " + nodeLimit

                        if (dryRun) {
                            description << "[DRY RUN]"
                        }

                        if (enableCreateZfsSnapshot) {
                            if (createZfsSnapshotName.length() < 1) {
                                error("Create ZFS snapshot name cannot be empty when create snapshot step is enabled");
                            }
                            
                            description << "creates (" + createZfsSnapshotName.trim() + ")"
                        }
                        if (params.ENABLE_DESTROY_ZFS_SNAPSHOT) {
                            if (destroyZfsSnapshotsNames.length() < 1) {
                                error("Destroy ZFS snapshot name cannot be empty when destroy snapshots step is enabled");
                            }

                            description << "destroys (" + destroyZfsSnapshotsNames.trim() + ")"
                        }
                        if (params.ENABLE_ROLLBACK_ZFS_SNAPSHOT) {
                            if (rollbackZfsSnapshotName.length() < 1) {
                                error("Rollback ZFS snapshot name cannot be empty when rollback snapshot step is enabled");
                            }

                            description << "rollbacks (" + rollbackZfsSnapshotName + ")"
                        }
                        
                        if (params.NODE?.toLowerCase() == 'all') {
                            ansibleLimit = environmentName
                        } else if (nodeLimit.trim()) {
                            ansibleLimit = nodeLimit.trim()
                        } else {
                            error "cannot run ansible: NODE parameter is not set"
                        }

                        
                        description << "runs on " + ansibleLimit

                        currentBuild.description = description.join(' ')
                    }
                }
            }

            stage('Create snapshot') {
                when {
                    expression { enableCreateZfsSnapshot } 
                }

                steps {
                    ansiblePublicPlaybook([
                        'playbookName': 'vega-network-zfs-snapshot',
                        'hostsLimit': ansibleLimit,
                        'dryRun': dryRun,
                        'ansibleTestnetAutomationBranch': ansibleTestnetAutomationBranch,
                        'networksConfigPrivateBranch': networksConfigPrivateBranch,
                        'withGitClone': true,
                        'extraVariables': [
                            'vega_zfs_snapshot_create_snapshot_name': createZfsSnapshotName
                        ]
                    ])
                }
            }

            stage('Rollback snapshot') {
                when {
                    expression { enableRollbackZfsSnapshot } 
                }

                steps {
                    ansiblePublicPlaybook([
                        'playbookName': 'vega-network-zfs-rollback',
                        'hostsLimit': ansibleLimit,
                        'dryRun': dryRun,
                        'ansibleTestnetAutomationBranch': ansibleTestnetAutomationBranch,
                        'networksConfigPrivateBranch': networksConfigPrivateBranch,
                        'withGitClone': true,
                        'extraVariables': [
                            'vega_zfs_snapshot_rollback_snapshot_name': rollbackZfsSnapshotName
                        ]
                    ])
                }
            }
            
            stage('Destroy snapshot') {
                when {
                    expression { enableDestroyZfsSnapshot } 
                }

                steps {
                    ansiblePublicPlaybook([
                        'playbookName': 'vega-network-zfs-destroy-snapshot',
                        'hostsLimit': ansibleLimit,
                        'dryRun': dryRun,
                        'ansibleTestnetAutomationBranch': ansibleTestnetAutomationBranch,
                        'networksConfigPrivateBranch': networksConfigPrivateBranch,
                        'withGitClone': true,
                        'extraVariables': [
                            'vega_zfs_snapshot_destroy_snapshots_names': destroyZfsSnapshotsNames
                        ]
                    ])
                }
            }
        } // stages
    } // pipeline
} // void call