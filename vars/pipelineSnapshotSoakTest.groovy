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
                        DIRS = DIRS.collectEntries{ basePath -> [
                            // generate suit names out of collected paths
                            (basePath): basePath.split('/').find { it.startsWith('system-tests-') }
                        ]}
                        parallel DIRS.collectEntries{ basePath, suit -> [
                            (suit): {
                                script {
                                    def tmHome = "${basePath}/tendermint/${params.NODE_NAME}"
                                    def vegaHome = "${basePath}/vega/${params.NODE_NAME}"
                                    def vegaBinary = "${basePath}/../tests/vega"
                                    // generate all of the snapshots by replaying the whole chain
                                    sh "./pv-snapshot-all --tm-home='${tmHome}' --vega-home='${vegaHome}' --vega-binary='${vegaBinary}' --replay"
                                    // now load from all of the snapshots
                                    sh "./pv-snapshot-all --tm-home='${tmHome}' --vega-home='${vegaHome}' --vega-binary='${vegaBinary}'"
                                }
                            }
                        ]}
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