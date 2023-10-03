void call() {
    // NOTE: environment variables PSSH_USER and PSSH_KEYFILE are used by veganet.sh script
    /* groovylint-disable-next-line NoDef, VariableTypeRequired */
    def sshCredentials = sshUserPrivateKey(
        credentialsId: 'ssh-vega-network',
        keyFileVariable: 'PSSH_KEYFILE',
        usernameVariable: 'PSSH_USER'
    )
    def githubAPICredentials = usernamePassword(
        credentialsId: vegautils.getVegaCiBotCredentials(),
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
        agent {
            label params.NODE_LABEL
        }
        options {
            skipDefaultCheckout()
            timeout(time: 20, unit: 'MINUTES')
            timestamps()
        }
        environment {
            PATH = "${env.WORKSPACE}/bin:${env.PATH}"
            GOBIN = "${env.WORKSPACE}/gobin"
        }
        stages {
            stage('CI Config') {
                steps {
                    sh "printenv"
                    echo "params=${params.inspect()}"
                    script {
                        vegautils.commonCleanup()
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
            stage('Devnet 1 - download latest checkpoint') {
                when {
                    expression { params.DEVNET_1 }
                }
                options { retry(3) }
                steps {
                    dir('checkpoint-store') {
                        withCredentials([sshCredentials]) {
                            sh label: 'Download latest checkpoint', script: """#!/bin/bash -e
                                go run scripts/main.go \
                                    download-latest \
                                    --network devnet1 \
                                    --ssh-user "\${PSSH_USER}" \
                                    --ssh-private-keyfile "\${PSSH_KEYFILE}" \
                                    --vega-home /home/vega/vega_home \
                                    --debug
                            """
                            sh 'git add devnet1/*'
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
                            sh 'git add fairground/*'
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
                            sh 'git add mainnet/*'
                        }
                    }
                }
            }
            stage('Commit changes') {
                steps {
                    dir('checkpoint-store') {
                        script {
                            def changesToCommit = sh(script:'git diff --cached', returnStdout:true).trim()
                            if (changesToCommit == '') {
                                print('No changes to commit')
                            } else {
                                sshagent(credentials: ['vega-ci-bot']) {
                                    sh 'git config --global user.email "vega-ci-bot@vega.xyz"'
                                    sh 'git config --global user.name "vega-ci-bot"'
                                    sh "git commit -m 'Automated update of checkpoints'"
                                    sh 'git pull origin main --rebase'
                                    sh "git push origin HEAD:main"
                                }
                            }
                        }
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
