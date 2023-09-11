def call() {
    if (!params.NODE) {
        SLAVES = Jenkins.instance.computers.findAll{ "${it.class}" == "class hudson.slaves.SlaveComputer" }.collect{ it.name }.collate(3)
    }
    else {
        SLAVES = params.NODE.replaceAll(" ", "").split(",").toList().collate(3)
    }
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
                        SLAVES.each{ slavesBatch ->
                            parallel slavesBatch.collectEntries { name -> [
                                (name): {
                                    node(name) {
                                        def labels = Jenkins
                                            .instance
                                            .computers
                                            .find{ "${it.name}" == name }
                                            .getAssignedLabels()
                                            .collect {it.toString()}
                                        ANSIBLE_VARS = writeJSON(
                                            returnText: true,
                                            json: [
                                                is_tiny: labels.contains('tiny'),
                                                is_medium: labels.find{ ['snapshot', 'core-build'] } ? true : false,
                                                is_big: labels.find{ ['office-system-tests', 'vega-market-sim', 'office-system-tests-lnl', 'performance-tests']}
                                            ]
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
                                                    dir('ansible') {
                                                        sh label: "ansible playbooks/proxmox.yaml", script: """#!/bin/bash -e
                                                            ansible-playbook \
                                                                --extra-vars '${ANSIBLE_VARS}'
                                                                ${params.DRY_RUN ? '--check' : ''} \
                                                                --diff \
                                                                playbooks/proxmox.yaml
                                                        """
                                                    }
                                                }
                                            }
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
