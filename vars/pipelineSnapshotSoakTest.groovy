def call() {
    pipeline {
        agent {
            label 'system-tests-capsule'
        }
        options {
            ansiColor('xterm')
            timestamps()
            timeout(time: 3, unit: 'HOURS')
        }
        environment {
            PATH = "${env.PATH}:${env.WORKSPACE}/bin"
        }
        stages {
            stage('Prepare') {
                failFast true
                parallel {
                    stage('Copy artifacts') {
                        steps {
                            // todo: copy only required artifacts to speed up?
                            dir('artifacts') {
                                copyArtifacts(
                                    projectName: params.SYSTEM_TEST_JOB_NAME,
                                    selector: specific("${params.SYSTEM_TEST_BUILD_NUMBER}"),
                                    fingerprintArtifacts: true,
                                    target: ".",
                                )
                            }
                        }
                    }
                    stage('Install vegatools') {
                        steps {
                            dir('bin') {
                                sh 'echo "xx" > xx'
                            }
                            gitClone([
                                url: 'git@github.com:' + 'vegaprotocol/vegatools' + '.git',
                                branch: params.VEGATOOLS_BRANCH,
                                directory: 'vegatools',
                                credentialsId: 'vega-ci-bot',
                                timeout: 2,
                            ])
                            script {
                                vegautils.buildGoBinary('vegatools', "${env.WORKSPACE}/bin/vegatools", './')
                            }
                        }
                    }
                    stage('Prepare script') {
                        steps {
                            writeFile (
                                text: libraryResource (
                                    resource: 'bin/pv-snapshot-all'
                                ),
                                file: 'pv-snapshot-all',
                            )
                            sh "chmod +x pv-snapshot-all"
                        }
                    }
                }
            }
            stage('Soak'){
                steps {
                    script {
                        DIRS = sh (
                            script: "find artifacts -type d -wholename '*testnet'",
                            returnStdout: true,
                        ).trim().split("\n").findAll{ it }
                        echo "dirs with testnet name: ${DIRS}"
                        DIRS = DIRS.collectEntries{ basePath -> [
                            // generate suit names out of collected paths
                            (basePath): basePath.split('/').find { it.startsWith('system-tests-') }
                        ]}
                        DIRS.collectEntries{ basePath, suit -> [
                            (suit): {
                                script {
                                    // it always needs to be node 2 (or 5 if its a network infra run) because that’ll be the non-validator node which means we need less setup
                                    def nodeName = basePath.contains('network_infra') ? '5' : '2'
                                    def tmHome = "tendermint/${nodeName}"
                                    def vegaHome = "vega/${nodeName}"
                                    def vegaBinary = "./../tests/vega"
                                    dir (basePath) {
                                        // generate all of the snapshots by replaying the whole chain
                                        sh "${env.WORKSPACE}/pv-snapshot-all --tm-home='${tmHome}' --vega-home='${vegaHome}' --vega-binary='${vegaBinary}' --replay"
                                        // now load from all of the snapshots
                                        sh "${env.WORKSPACE}/pv-snapshot-all --tm-home='${tmHome}' --vega-home='${vegaHome}' --vega-binary='${vegaBinary}'"
                                    }
                                }
                            }
                        ]}.each { stageName, codeToExecute -> stage(stageName) {
                            codeToExecute()
                        }}
                    }
                }
                post {
                    always {
                        archiveArtifacts(
                            artifacts: "**/**/node-**.log",
                            allowEmptyArchive: true,
                        )
                        archiveArtifacts(
                            artifacts: "**/**/err-node-**.log",
                            allowEmptyArchive: true,
                        )
                    }
                }
            }
        }
        post {
            always {
                cleanWs()
            }
        }
    }
}
