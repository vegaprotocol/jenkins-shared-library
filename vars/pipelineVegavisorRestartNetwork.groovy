void call() {
    // NOTE: environment variables PSSH_USER and PSSH_KEYFILE are used by veganet.sh script
    /* groovylint-disable-next-line NoDef, VariableTypeRequired */
    def sshCredentials = sshUserPrivateKey(
        credentialsId: 'ssh-vega-network',
        keyFileVariable: 'PSSH_KEYFILE',
        usernameVariable: 'PSSH_USER'
    )
    Map dockerCredentials = [
        credentialsId: 'github-vega-ci-bot-artifacts',
        url: 'https://ghcr.io'
    ]
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
            timeout(time: 40, unit: 'MINUTES')
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
            stage('Checkout') {
                parallel {
                    stage('vega'){
                        when {
                            expression { params.VEGA_VERSION }
                        }
                        steps {
                            script {
                                doGitClone('vega', params.VEGA_VERSION)
                            }
                        }
                    }
                    // stage('vegacapsule'){
                    //     steps {
                    //         script {
                    //             doGitClone('vegacapsule', params.VEGACAPSULE_BRANCH)
                    //         }
                    //     }
                    // }
                    // stage('devopsscripts'){
                    //     steps {
                    //         script {
                    //             doGitClone('devopsscripts', params.DEVOPSSCRIPTS_BRANCH)
                    //         }
                    //     }
                    // }
                    stage('ansible'){
                        steps {
                            script {
                                doGitClone('ansible', params.ANSIBLE_BRANCH)
                            }
                        }
                    }
                    // stage('networks-internal') {
                    //     steps {
                    //         script {
                    //             doGitClone('networks-internal', params.NETWORKS_INTERNAL_BRANCH)
                    //         }
                    //     }
                    // }
                }
            }
            stage('Prepare') {
                parallel {
                    stage('Build vaga, data-node, vegawallet and visor') {
                        when {
                            expression { params.VEGA_VERSION }
                        }
                        steps {
                            dir('vega') {
                                sh label: 'Compile', script: """#!/bin/bash -e
                                    go build -v \
                                        -o ../bin/ \
                                        ./cmd/vega \
                                        ./cmd/data-node \
                                        ./cmd/vegawallet \
                                        ./cmd/visor
                                """
                            }
                            dir('bin') {
                                sh label: 'Sanity check: vega', script: '''#!/bin/bash -e
                                    file ./vega
                                    ./vega version
                                '''
                                sh label: 'Sanity check: data-node', script: '''#!/bin/bash -e
                                    file ./data-node
                                    ./data-node version
                                '''
                                sh label: 'Sanity check: vegawallet', script: '''#!/bin/bash -e
                                    file ./vegawallet
                                    ./vegawallet version
                                '''
                                sh label: 'Sanity check: visor', script: '''#!/bin/bash -e
                                    file ./visor
                                    ./visor --help
                                '''
                            }
                        }
                    }
                    // stage('Build vegacapsule') {
                    //     steps {
                    //         dir('vegacapsule') {
                    //             sh label: 'Compile', script: '''#!/bin/bash -e
                    //                 go build -v \
                    //                     -o ../bin/vegacapsule \
                    //                     ./main.go
                    //             '''
                    //         }
                    //         dir('bin') {
                    //             sh label: 'Sanity check: vegacapsule', script: '''#!/bin/bash -e
                    //                 file ./vegacapsule
                    //                 ./vegacapsule --help
                    //             '''
                    //         }
                    //     }
                    // }
                    // stage('Setup devopsscripts') {
                    //     steps {
                    //         dir('devopsscripts') {
                    //             sh label: 'Download dependencies', script: '''#!/bin/bash -e
                    //                 go mod download
                    //             '''
                    //             withCredentials([githubAPICredentials]) {
                    //                 sh label: 'Setup secret', script: '''#!/bin/bash -e
                    //                     printf "%s" "$GITHUB_API_TOKEN" > ./secret.txt
                    //                 '''
                    //             }
                    //             sh label: 'Sanity check: devopsscripts', script: """#!/bin/bash -e
                    //                 go run main.go smart-contracts get-status --network "${env.NET_NAME}"
                    //             """
                    //         }
                    //     }
                    // }
                }
            }  // End: Prepare
            stage('Restart Network') {
                when {
                    expression { env.ANSIBLE_LIMIT }
                }
                environment {
                    ANSIBLE_VAULT_PASSWORD_FILE = credentials('ansible-vault-password')
                    HASHICORP_VAULT_ADDR = 'https://vault.ops.vega.xyz'
                }
                steps {
                    script {
                        if (params.VEGA_VERSION) {
                            sh label: 'copy binaries to ansible', script: """#!/bin/bash -e
                                cp ./bin/vega ./ansible/roles/barenode/files/bin/
                                cp ./bin/data-node ./ansible/roles/barenode/files/bin/
                                cp ./bin/visor ./ansible/roles/barenode/files/bin/
                            """
                        }
                    }
                    dir('ansible') {
                        withCredentials([usernamePassword(credentialsId: 'hashi-corp-vault-jenkins-approle', passwordVariable: 'HASHICORP_VAULT_SECRET_ID', usernameVariable:'HASHICORP_VAULT_ROLE_ID')]) {
                            withCredentials([sshCredentials]) {
                                // Note: environment variables PSSH_KEYFILE and PSSH_USER
                                //        are set by withCredentials wrapper
                                sh label: 'ansible playbook run', script: """#!/bin/bash -e
                                    ansible-playbook \
                                        --diff \
                                        -u "\${PSSH_USER}" \
                                        --private-key "\${PSSH_KEYFILE}" \
                                        --inventory inventories \
                                        --limit "${env.ANSIBLE_LIMIT}" \
                                        -e '{"restart_network": true, "release_version": "${params.RELEASE_VERSION}"}' \
                                        playbooks/playbook-barenode.yaml
                                """
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
