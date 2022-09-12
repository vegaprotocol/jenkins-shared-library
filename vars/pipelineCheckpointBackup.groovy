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
                steps {
                    dir('checkpoint-store') {
                        withCredentials([sshCredentials]) {
                            sh label: 'Download latest checkpoint', script: """#!/bin/bash -e
                                go run scripts/main.go \
                                    download-latest \
                                    --network devnet \
                                    --ssh-user "${env.PSSH_USER}" \
                                    --ssh-private-keyfile "${env.PSSH_KEYFILE}"
                            """
                        }
                    }
                }
            }
            stage('Devnet 3 - download latest checkpoint') {
                when {
                    expression { params.DEVNET_3 }
                }
                steps {
                    dir('checkpoint-store') {
                        withCredentials([sshCredentials]) {
                            sh label: 'Download latest checkpoint', script: """#!/bin/bash -e
                                go run scripts/main.go \
                                    download-latest \
                                    --network devnet3 \
                                    --ssh-user "${env.PSSH_USER}" \
                                    --ssh-private-keyfile "${env.PSSH_KEYFILE}" \
                                    --vega-home /home/vega/vega_home
                            """
                        }
                    }
                }
            }
            stage('Fairground - download latest checkpoint') {
                when {
                    expression { params.FAIRGROUND }
                }
                steps {
                    dir('checkpoint-store') {
                        withCredentials([sshCredentials]) {
                            sh label: 'Download latest checkpoint', script: """#!/bin/bash -e
                                go run scripts/main.go \
                                    download-latest \
                                    --network fairground \
                                    --ssh-user "${env.PSSH_USER}" \
                                    --ssh-private-keyfile "${env.PSSH_KEYFILE}"
                            """
                        }
                    }
                }
            }
            stage('Mainnet - download latest checkpoint') {
                when {
                    expression { params.MAINNET }
                }
                steps {
                    dir('checkpoint-store') {
                        withCredentials([sshCredentials]) {
                            sh label: 'Download latest checkpoint', script: """#!/bin/bash -e
                                go run scripts/main.go \
                                    download-latest \
                                    --network mainnet \
                                    --ssh-user "${env.PSSH_USER}" \
                                    --ssh-private-keyfile "${env.PSSH_KEYFILE}" \
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
                            git diff
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
