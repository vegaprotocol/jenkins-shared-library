def call() {
    if (!params.NODE) {
        SLAVES = Jenkins.instance.computers.findAll{ "${it.class}" == "class hudson.slaves.SlaveComputer" }.collect{ it.name }.collate(5)
    }
    else {
        SLAVES = params.NODE.replaceAll(" ", "").split(",").toList().collate(5)
    }
    pipeline {
        agent {
            label 'tiny'
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
                                        cleanWs()
                                        retry (3) {
                                            checkout scm
                                        }
                                        timeout(time: 75, unit: 'MINUTES') {
                                            sshagent(credentials: ['vega-ci-bot']) {
                                                sh 'ansible-playbook playbooks/proxmox.yaml'
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
