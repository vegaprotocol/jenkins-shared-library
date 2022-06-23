pipeline {
    agent any
    environment {
        // TO-DO: Add secrets to jenkins
        // NOMAD_TLS_SERVER_NAME = "server.global.nomad"
        // NOMAD_CACERT = credentials('nomad-cacert')
        // NOMAD_CLIENT_CERT = credentials('nomad-client-cert')
        // NOMAD_CLIENT_KEY = credentials('nomad-client-key')

        // AWS_SECRET_ACCESS_KEY = credentials('aws-secret')
        // AWS_ACCESS_KEY_ID = credentials('aws-id')

        PATH = "${env.PWD}/bin:${env.PATH}"

        GITHUB_CREDS = "github-vega-ci-bot-artifacts"
    }
    stages {
        stage('Init bin') {
            steps {
                sh "mkdir bin"
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
                }
                stage('Download vega binary') {
                    steps {
                        withGHCLI('credentialsId': env.GITHUB_CREDS) {
                            "gh release --repo vegaprotocol/vega download ${params.VEGA_VERSION} --pattern '*linux*'"
                        }
                        sh "mv vega-linux-amd64 bin/vega"
                        sh "chmod +x bin/vega"
                        sh "vega version"
                    }
                }
                stage('Download data-node binary') {
                    steps {
                        withGHCLI('credentialsId': env.GITHUB_CREDS) {
                            "gh release --repo vegaprotocol/data-node download ${params.DATA_NODE_VERSION} --pattern '*linux*'"
                        }
                        sh "mv data-node-linux-amd64 bin/data-node"
                        sh "chmod +x bin/data-node"
                        sh "data-node version"
                    }
                }
            }
        }
    }
}