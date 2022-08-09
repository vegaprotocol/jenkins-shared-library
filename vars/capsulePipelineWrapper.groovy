def call() {
    writeConfigs = { envName ->
        sh label: "generate templates: ${envName}", script: """
            vegacapsule template genesis \
                --home-path '${env.CONFIG_HOME}' \
                --path '${env.TEMPLATES_HOME}/genesis.tmpl.json' ${env.FLAGS}

            vegacapsule template node-sets \
                --home-path '${env.CONFIG_HOME}' \
                --path '${env.TEMPLATES_HOME}/vega.validators.tmpl.toml' \
                --nodeset-group-name validators \
                --type vega \
                --with-merge ${env.FLAGS}

            vegacapsule template node-sets \
                --home-path '${env.CONFIG_HOME}' \
                --path '${env.TEMPLATES_HOME}/tendermint.validators.tmpl.toml' \
                --nodeset-group-name validators \
                --type tendermint \
                --with-merge ${env.FLAGS}

            vegacapsule template node-sets \
                --home-path '${env.CONFIG_HOME}' \
                --path '${env.TEMPLATES_HOME}/vega.full.tmpl.toml' \
                --nodeset-group-name full \
                --type vega \
                --with-merge ${env.FLAGS}

            vegacapsule template node-sets \
                --home-path '${env.CONFIG_HOME}' \
                --path '${env.TEMPLATES_HOME}/tendermint.full.tmpl.toml' \
                --nodeset-group-name full \
                --type tendermint \
                --with-merge ${env.FLAGS}

            vegacapsule template node-sets \
                --home-path '${env.CONFIG_HOME}' \
                --path '${env.TEMPLATES_HOME}/data_node.full.tmpl.toml' \
                --nodeset-group-name full \
                --type data-node \
                --with-merge ${env.FLAGS}
            """
    }

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
            CONFIG_HOME = "${env.WORKSPACE}/${env.NET_NAME}/vegacapsule/home"
        }
        stages {
            stage('Init bin') {
                steps {
                    sh "mkdir -p bin"
                }
            }
            stage('Init binaries') {
                parallel {
                    stage('Check out vegaprotocol/networks-internal') {
                        when {
                            expression {
                                params.REGENERATE_CONFIGS
                            }
                        }
                        steps {
                            // gh didn't work, don't know why, just replaced with jenkins native check out
                            gitClone(
                                url: "git@github.com:vegaprotocol/networks-internal.git",
                                credentialsId: 'vega-ci-bot',
                                dir: 'networks-internal'
                            )
                        }
                    }
                    stage('Build capsule') {
                        when {
                            expression {
                                params.BUILD_CAPSULE
                            }
                        }
                        steps {
                            gitClone(
                                url: "git@github.com:vegaprotocol/vegacapsule.git",
                                credentialsId: 'vega-ci-bot',
                                dir: 'vegacapsule',
                                branch: params.VEGACAPSULE_VERSION,
                            )
                            dir('vegacapsule') {
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
                            sh """
                                mkdir -p '${env.CONFIG_HOME}'
                                aws s3 sync '${env.S3_CONFIG_HOME}/' '${env.CONFIG_HOME}/'
                            """
                            sh "if [ -d '${env.CONFIG_HOME}/config.hcl' ]; then cat '${env.CONFIG_HOME}/config.hcl'; fi"
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
                    sh "vegacapsule network stop --nodes-only --home-path '${env.CONFIG_HOME}'"
                }
            }
            stage('Update networks configs') {
                environment {
                    TEMPLATES_HOME = "${env.WORKSPACE}/networks-internal/${env.NET_NAME}/vegacapsule/config"
                }
                when {
                    expression {
                        params.REGENERATE_CONFIGS
                    }
                }
                stages {
                    stage('Template live config (git / networks-internal)') {
                        environment {
                            FLAGS = "--out-dir '${env.WORKSPACE}/networks-internal/stagnet3/live-config'"
                        }
                        steps {
                            script {
                                writeConfigs('live config (git / networks-internal)')
                            }
                        }
                    }
                    stage('Template s3 config') {
                        environment {
                            FLAGS = '--update-network'
                        }
                        steps {
                            script {
                                writeConfigs('s3')
                            }
                        }
                    }
                    stage('Write to git') {
                        steps {
                            makeCommit(
                                makeCheckout: false,
                                directory: 'networks-internal',
                                branchName: 'live-config-update',
                                commitMessage: '[Automated] live config update',
                            )
                        }
                    }
                }
            }
            stage('Restart Network') {
                when {
                    expression {
                        (params.ACTION == 'RESTART' || params.ACTION == 'START') && params.UNSAFE_RESET_ALL
                    }
                }
                steps {
                    sh "vegacapsule nodes unsafe-reset-all --remote --home-path '${env.CONFIG_HOME}'"
                }
            }
            stage('Start Network') {
                when {
                    expression {
                        params.ACTION == 'START' || params.ACTION == 'RESTART'
                    }
                }
                steps {
                    sh "vegacapsule network start --home-path '${env.CONFIG_HOME}'"
                }
            }
            stage('Write to s3'){
                options {
                    retry(3)
                }
                steps {
                    sh label: "sync configs to s3", script: """
                        aws s3 sync '${env.CONFIG_HOME}/' '${env.S3_CONFIG_HOME}/'
                    """
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