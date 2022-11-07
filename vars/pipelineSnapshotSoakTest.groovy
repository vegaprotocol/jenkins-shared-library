def call() {
    DIRS = []
    pipeline {
        agent {
            label 'system-tests-capsule'
        }
        options {
            ansiColor('xterm')
            timestamps()
        }
        stages {
            stage('Prepare') {
                steps {
                    writeFile (
                        text: libraryResource (
                            resource: 'bin/pv-snapshot-all'
                        ),
                        file: 'pv-snapshot-all',
                    )
                    sh "chmod +x pv-snapshot-all"
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
            stage('Soak'){
                steps {
                    script {
                        DIRS = sh (
                            script: "find artifacts -type d -wholename '*testnet'",
                            returnStdout: true,
                        ).trim().split("\n").findAll{ it }
                    }
                    parallel DIRS.collectEntries{ basePath ->
                        [
                            // use name of suit as name of the stage
                            (basePath.split('/').find { it.startsWith('system-tests-') }) : {
                                // generate all of the snapshots by replaying the whole chain
                                sh "./pv-snapshot-all --tm-home='${basePath}/tendermint/${params.NODE_NAME}' --vega-home=${basePath}/vega/${params.NODE_NAME} --replay"
                                // now load from all of the snapshots
                                sh "./pv-snapshot-all --tm-home='${basePath}/tendermint/${params.NODE_NAME}' --vega-home=${basePath}/vega/${params.NODE_NAME}"
                            }
                        ]
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