def call() {
    pipeline {
        agent {
            label params.NODE_LABEL
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
                            script {
                                publicIP = agent.getPublicIP()
                                print("The box public IP is: " + publicIP)

                                String jobFullPath = params.SYSTEM_TEST_JOB_NAME.split('/').join('/job/')
                                print("Copying artifact from build: https://jenkins.ops.vega.xyz/job/" + jobFullPath + "/" + params.SYSTEM_TEST_BUILD_NUMBER)
                            }

                            // todo: copy only required artifacts to speed up?
                            dir('artifacts') {
                                copyArtifacts(
                                    projectName: params.SYSTEM_TEST_JOB_NAME,
                                    selector: specific("${params.SYSTEM_TEST_BUILD_NUMBER}"),
                                    fingerprintArtifacts: true,
                                    target: ".",
                                )
                                sh "ls -al"
                            }
                        }
                    }
                    stage('Prepare script') {
                        steps {
                            writeFile (
                                text: libraryResource (
                                    resource: 'bin/snapshot-soak-test'
                                ),
                                file: 'snapshot-soak-test',
                            )
                            sh '''
                                chmod +x snapshot-soak-test
                                python3 -m venv venv
                                . venv/bin/activate
                                pip3 install toml
                                pip3 install requests
                                ./snapshot-soak-test --help
                            '''
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
                        if (DIRS.isEmpty()) {
                            error("Test directories are empty, most probably copy artifcats are configured wrong way or upstream build has failed.")
                        }
                        // scenario for multiple suit cases at once
                        if (DIRS.size() > 1) {
                            DIRS = DIRS.collectEntries{ basePath -> [
                                // generate suit names out of collected paths
                                (basePath): basePath.split('/').find { it.startsWith('system-tests-') }
                            ]}
                            DIRS.collectEntries{ basePath, suit -> [
                                (suit): {
                                    script {
                                        print('''Running scenario for multiple suit cases at once, suit: "''' + suit + '''" for for base path "''' + basePath + '''"''')
                                        // it always needs to be node 2 (or 5 if its a network infra run) because that'll be the non-validator node which means we need less setup
                                        def nodeName = basePath.contains('network_infra') ? 'node5' : 'node2'
                                        def tmHome = "tendermint/${nodeName}"
                                        def vegaHome = "vega/${nodeName}"
                                        def vegaBinary = "./../tests/vega"
                                        dir(basePath) {
                                            // prepare venv
                                            // generate all of the snapshots by replaying the whole chain
                                            // now load from all of the snapshots
                                            sh """
                                                . ${env.WORKSPACE}/venv/bin/activate
                                                ${env.WORKSPACE}/snapshot-soak-test --tm-home='${tmHome}' --vega-home='${vegaHome}' --vega-binary='${vegaBinary}' --replay
                                                ${env.WORKSPACE}/snapshot-soak-test --tm-home='${tmHome}' --vega-home='${vegaHome}' --vega-binary='${vegaBinary}'
                                            """
                                        }
                                    }
                                }
                            ]}.each { stageName, codeToExecute -> stage(stageName) {
                                codeToExecute()
                            }}
                        }
                        // scenario for 1 suit case at the time
                        else {
                            DIR = DIRS[0]

                            print('''Running scenario for 1 suit case at the time for for base path "''' + DIR + '''"''')
                            def nodeName = params.SUIT_NAME.contains('network_infra') ? 'node5' : 'node2'
                            def tmHome = "tendermint/${nodeName}"
                            def vegaHome = "vega/${nodeName}"
                            def vegaBinary = "../vega"
                            dir(DIR) {
                                // prepare venv
                                // generate all of the snapshots by replaying the whole chain
                                // now load from all of the snapshots
                                sh """
                                    ls -al tendermint
                                    ls -al vega
                                    ls -al ../
                                    ls -al ../../
                                    . ${env.WORKSPACE}/venv/bin/activate
                                    ${env.WORKSPACE}/snapshot-soak-test --tm-home='${tmHome}' --vega-home='${vegaHome}' --vega-binary='${vegaBinary}' --replay
                                    ${env.WORKSPACE}/snapshot-soak-test --tm-home='${tmHome}' --vega-home='${vegaHome}' --vega-binary='${vegaBinary}'
                                """
                            }

                        }
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
