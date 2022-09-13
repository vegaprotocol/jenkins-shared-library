void call() {
    // NOTE: environment variables PSSH_USER and PSSH_KEYFILE are used by veganet.sh script
    /* groovylint-disable-next-line NoDef, VariableTypeRequired */
    def sshCredentials = sshUserPrivateKey(
        credentialsId: 'ssh-vega-network',
        keyFileVariable: 'PSSH_KEYFILE',
        usernameVariable: 'PSSH_USER'
    )
    def githubAPICredentials = usernamePassword(
        credentialsId: 'github-vega-ci-bot-artifacts',
        passwordVariable: 'GITHUB_API_TOKEN',
        usernameVariable: 'GITHUB_API_USER'
    )

    def doGitClone = { repo, branch ->
        dir(repo) {
            retry(3) {
                // returns object:
                // [GIT_BRANCH:origin/master,
                // GIT_COMMIT:5897d0e927e920fc217f967e91ea086f8cf2bb41,
                // GIT_PREVIOUS_COMMIT:5897d0e927e920fc217f967e91ea086f8cf2bb41,
                // GIT_PREVIOUS_SUCCESSFUL_COMMIT:5897d0e927e920fc217f967e91ea086f8cf2bb41, 
                // GIT_URL:git@github.com:vegaprotocol/devops-infra.git]
                return checkout([
                    $class: 'GitSCM',
                    branches: [[name: branch]],
                    userRemoteConfigs: [[
                        url: "git@github.com:vegaprotocol/${repo}.git",
                        credentialsId: 'vega-ci-bot'
                    ]]])
            }
        }
    }

    pipeline {
        agent any
        options {
            skipDefaultCheckout()
            timeout(time: 20, unit: 'MINUTES')
            timestamps()
        }
        environment {
            PATH = "${env.WORKSPACE}/bin:${env.PATH}"
        }
        stages {
            stage('CI Config') {
                steps {
                    sh "printenv"
                    echo "params=${params.inspect()}"
                    script {
                        publicIP = agent.getPublicIP()
                        print("Jenkins Agent public IP is: " + publicIP)
                    }
                }
            }
            stage('Git clone'){
                steps {
                    script {
                        doGitClone('checkpoint-store', params.CHECKPOINT_STORE_BRANCH)
                    }
                }
            }
            stage('Dependencies') {
                options { retry(3) }
                steps {
                    dir('checkpoint-store/scripts') {
                        sh '''#!/bin/bash -e
                            go mod download -x
                        '''
                    }
                }
            }
            stage('Devnet - download latest checkpoint') {
                when {
                    expression { params.DEVNET }
                }
                options { retry(3) }
                steps {
                    dir('checkpoint-store') {
                        withCredentials([sshCredentials]) {
                            sh label: 'Download latest checkpoint', script: """#!/bin/bash -e
                                go run scripts/main.go \
                                    download-latest \
                                    --network devnet \
                                    --ssh-user "\${PSSH_USER}" \
                                    --ssh-private-keyfile "\${PSSH_KEYFILE}"
                            """
                        }
                    }
                }
            }
            stage('Devnet 3 - download latest checkpoint') {
                when {
                    expression { params.DEVNET_3 }
                }
                options { retry(3) }
                steps {
                    dir('checkpoint-store') {
                        withCredentials([sshCredentials]) {
                            sh label: 'Download latest checkpoint', script: """#!/bin/bash -e
                                go run scripts/main.go \
                                    download-latest \
                                    --network devnet3 \
                                    --ssh-user "\${PSSH_USER}" \
                                    --ssh-private-keyfile "\${PSSH_KEYFILE}" \
                                    --vega-home /home/vega/vega_home \
                                    --debug
                            """
                        }
                    }
                }
            }
            stage('Fairground - download latest checkpoint') {
                when {
                    expression { params.FAIRGROUND }
                }
                options { retry(3) }
                steps {
                    dir('checkpoint-store') {
                        withCredentials([sshCredentials]) {
                            sh label: 'Download latest checkpoint', script: """#!/bin/bash -e
                                go run scripts/main.go \
                                    download-latest \
                                    --network fairground \
                                    --ssh-user "\${PSSH_USER}" \
                                    --ssh-private-keyfile "\${PSSH_KEYFILE}"
                            """
                        }
                    }
                }
            }
            stage('Mainnet - download latest checkpoint') {
                when {
                    expression { params.MAINNET }
                }
                options { retry(3) }
                steps {
                    dir('checkpoint-store') {
                        withCredentials([sshCredentials]) {
                            sh label: 'Download latest checkpoint', script: """#!/bin/bash -e
                                go run scripts/main.go \
                                    download-latest \
                                    --network mainnet \
                                    --ssh-user "\${PSSH_USER}" \
                                    --ssh-private-keyfile "\${PSSH_KEYFILE}" \
                                    --vega-home /home/vega/vega_volume/vega
                            """
                        }
                    }
                }
            }
            stage('Commit changes') {
                steps {
                    dir('checkpoint-store') {
                        sh label: 'Commit changes', script: """#!/bin/bash -e
                            echo pwd
                            pwd
                            echo diff
                            git diff
                            echo add
                            git add .
                            echo diff
                            git diff
                            echo status
                            git status
                        """
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
