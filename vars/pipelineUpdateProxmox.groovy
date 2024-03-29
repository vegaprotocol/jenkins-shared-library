def call() {
    selectedServers = vegautils.proxmoxNodeSelector(
        providedNode: params.NODE,
        collateParam: 6
    )
    pipeline {
        agent {
            label 'tiny-cloud'
        }
        environment {
            GOBIN = "${env.WORKSPACE}/gobin"
        }
        options {
            timestamps()
            ansiColor('xterm')
        }
        post {
            always {
                cleanWs()
            }
        }
        stages {
            stage('prepare') {
                steps {
                    script {
                        vegautils.commonCleanup()
                    }
                }
            }
            stage('trigger provisioner') {
                when {
                    anyOf {
                        changeset "roles/jenkins-agent/**"
                        changeset "playbooks/proxmox.yaml"
                        triggeredBy 'UserIdCause'
                    }
                }
                steps {
                    script {
                        // implement logic that waits for jobs to be completed and blocks agents from scheduling new jobs...
                        selectedServers.each{ serversBatch ->
                            parallel serversBatch.collectEntries { name -> [
                                (name): {
                                    node(name) {
                                        def labels = vegautils.nodeLabels(name)
                                        def jsonData = [
                                            is_tiny: false,
                                            is_medium: false,
                                            is_big: false,
                                        ]
                                        if (labels.find{ ['office-system-tests', 'vega-market-sim', 'office-system-tests-lnl', 'performance-tests'].contains(it) }) {
                                            jsonData['is_big'] = true
                                        }
                                        else if (labels.find{ ['snapshot', 'core-build'].contains(it) }) {
                                            jsonData['is_medium'] = true
                                        }
                                        else if (labels.contains('tiny')) {
                                            jsonData['is_tiny'] = true
                                        }
                                        def ansibleVars = writeJSON(
                                            returnText: true,
                                            json: jsonData,
                                        )
                                        cleanWs()
                                        catchError(buildResult: 'UNSTABLE') {
                                            gitClone(
                                                vegaUrl: 'ansible',
                                                directory: 'ansible',
                                                branch: params.ANSIBLE_BRANCH,
                                            )
                                            timeout(time: 75, unit: 'MINUTES') {
                                                sshagent(credentials: ['vega-ci-bot']) {
                                                    withCredentials([file(credentialsId: 'ansible-vault-password', variable: 'ANSIBLE_VAULT_PASSWORD_FILE')]) {
                                                        dir('ansible') {
                                                            sh label: "ansible playbooks/proxmox.yaml", script: """#!/bin/bash -e
                                                                ansible-playbook \
                                                                    --extra-vars '${ansibleVars}' \
                                                                    ${params.DRY_RUN ? '--check' : ''} \
                                                                    --diff \
                                                                    playbooks/proxmox.yaml
                                                            """
                                                            if (name == 'jenkins15') {
                                                                sh label: "ansible playbooks/proxmox.yaml", script: """#!/bin/bash -e
                                                                    ansible-playbook \
                                                                        --extra-vars '{"proxmox_exporter": true}' \
                                                                        ${params.DRY_RUN ? '--check' : ''} \
                                                                        --diff \
                                                                        playbooks/playbook-exporters.yaml
                                                                """
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                       timeout(time: 30, unit: 'SECONDS') {
                                            try {
                                                sh 'sudo systemctl reboot'
                                            } catch (e) {}
                                        }
    

                                        
                                    }
                                }
                            ]}
                        }
                    }
                }
            }
        }
    }
}
