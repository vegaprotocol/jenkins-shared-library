def call() {
    pipeline {
        agent any
        environment {
            // TO-DO: Add secrets to jenkins
            NOMAD_TLS_SERVER_NAME = "server.global.nomad"
            NOMAD_CACERT = credentials('nomad-cacert')
            NOMAD_CLIENT_CERT = credentials('nomad-client-cert')
            NOMAD_CLIENT_KEY = credentials('nomad-client-key')

            AWS_SECRET_ACCESS_KEY = credentials('jenkins-vegacapsule-aws-secret')
            AWS_ACCESS_KEY_ID = credentials('jenkins-vegacapsule-aws-id')

            PATH = "${env.WORKSPACE}/bin:${env.PATH}"
            GITHUB_CREDS = "github-vega-ci-bot-artifacts"
            CONFIG_HOME = "${env.WORKSPACE}/stagnet3/vegacapsule/home"
        }
        stages {
            stage('Init bin') {
                steps {
                    sh "mkdir -p bin"
                }
            }
            stage('Init binaries') {
                parallel {
                    stage('Build capsule') {
                        when {
                            expression {
                                params.BUILD_CAPSULE
                            }
                        }
                        steps {
                            withGHCLI('credentialsId': env.GITHUB_CREDS) {
                                sh "gh repo clone vegaprotocol/vegacapsule"
                            }
                            dir('vegacapsule') {
                                sh "git checkout ${params.VEGACAPSULE_VERSION}"
                                sh "go build -o vegacapsule ."
                                sh "mv vegacapsule ../bin"
                            }
                            sh "chmod +x bin/vegacapsule"
                            sh "vegacapsule --help"
                        }
                    }
                    stage('Download capsule') {
                        when {
                            not {
                                expression {
                                    params.BUILD_CAPSULE
                                }
                            }
                        }
                        steps {
                            withGHCLI('credentialsId': env.GITHUB_CREDS) {
                                sh "gh release --repo vegaprotocol/vegacapsule download ${params.VEGACAPSULE_VERSION} --pattern '*linux*'"
                            }
                            sh "unzip vegacapsule-linux-amd64.zip"
                            sh "mv vegacapsule bin/"
                            sh "chmod +x bin/vegacapsule"
                            sh "vegacapsule --help"
                        }
                    }
                    stage('Download vega binary') {
                        steps {
                            withGHCLI('credentialsId': env.GITHUB_CREDS) {
                                sh "gh release --repo vegaprotocol/vega download ${params.VEGA_VERSION} --pattern '*linux*'"
                            }
                            sh "mv vega-linux-amd64 bin/vega"
                            sh "chmod +x bin/vega"
                            sh "vega version"
                        }
                    }
                    stage('Download data-node binary') {
                        steps {
                            withGHCLI('credentialsId': env.GITHUB_CREDS) {
                                sh "gh release --repo vegaprotocol/data-node download ${params.DATA_NODE_VERSION} --pattern '*linux*'"
                            }
                            sh "mv data-node-linux-amd64 bin/data-node"
                            sh "chmod +x bin/data-node"
                            sh "data-node version"
                        }
                    }
                    stage('Sync remote state to local') {
                        steps {
                            sh "mkdir -p ${env.CONFIG_HOME}"
                            sh "aws s3 sync ${env.S3_CONFIG_HOME}/ ${env.CONFIG_HOME}/"
                            sh "sed -i 's/vega_binary_path=.*/vega_binary_path=vega/g' config.hcl"
                            sh "cat ${env.CONFIG_HOME}/config.hcl"
                        }
                    }
                }
            }
            stage('Stop Network') {
                when {
                    expression {
                        params.ACTION == 'STOP' || params.ACTION == 'RESTART'
                    }
                }
                steps {
                    sh "vegacapsule network stop --home-path ${env.CONFIG_HOME}"
                }
            }
            stage('Restart Network') {
                when {
                    expression {
                        params.ACTION == 'RESTART' && params.UNSAFE_RESET_ALL
                    }
                }
                steps {
                    sh "vegacapsule nodes unsafe-reset-all --home-path ${env.CONFIG_HOME}"
                }
            }
            stage('Start Network') {
                when {
                    expression {
                        params.ACTION == 'START' || params.ACTION == 'RESTART'
                    }
                }
                steps {
                    sh "vegacapsule network start --home-path ${env.CONFIG_HOME}"
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