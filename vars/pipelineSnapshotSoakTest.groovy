def call() {
    STEPS = [:]
    pipeline {
        agent 'system-tests-capsule'
        options {
            ansiColor('xterm')
            timestamps()
        }
        stages {
            stage('Prepare') {
                writeFile (
                    text: libraryResource (
                        resource: 'bin/pv-snapshot-all'
                    ),
                    file: 'pv-snapshot-all',
                )
                sh "chmod +x pv-snapshot-all"
                sh "pip3 install toml"
                dir('artifacts') {
                    copyArtifacts(
                        projectName: params.SYSTEM_TEST_JOB_NAME,
                        selector: specific("${params.SYSTEM_TEST_BUILD_NUMBER}"),
                        fingerprintArtifacts: true,
                        target: ".",
                    )
                }
            }
            stage('Soak'){
                steps {
                    script {
                        def stepsKeys = sh (
                            script: "find . -type d -wholename '*testnet'",
                            returnStodut: true,
                        ).trim().split("\n").findAll{ it }
                        STEPS = stepsKeys.collectEntries{ tmPath -> [

                            // use name of suit as name of the stage
                            (
                                basePath.split('/').find {
                                    it.startsWith('system-tests-')
                                }
                            ) : {
                                // generate all of the snapshots by replaying the whole chain
                                sh "./pv-snapshot-all --tm-home='${basePath}/tendermint/${params.NODE_NAME}' --vega-home=${basePath}/vega/${params.NODE_NAME} --replay"
                                // now load from all of the snapshots
                                sh "./pv-snapshot-all --tm-home='${basePath}/tendermint/${params.NODE_NAME}' --vega-home=${basePath}/vega/${params.NODE_NAME}"
                            }
                        ]}
                    }
                    parallel STEPS
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