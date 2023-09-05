def call() {
    if (!params.NODE) {
        SLAVES = Jenkins.instance.computers.findAll{ "${it.class}" == "class hudson.slaves.SlaveComputer" }.collect{ it.name }
    }
    else {
        SLAVES = params.NODE.replaceAll(" ", "").split(",")
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
            timeout(time: 10, unit: 'MINUTES')
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
                        parallel SLAVES.collectEntries { name -> [
                            (name): {
                                node(name) {
                                    cleanWs()
                                    retry (3) {
                                        checkout scm
                                    }
                                    sshagent(credentials: ['vega-ci-bot']) {
                                        sh 'ansible-playbook playbooks/proxmox.yaml'
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
