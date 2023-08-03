def call() {
    if (!params.NODE) {
        SLAVES = Jenkins.instance.computers.findAll{ "${it.class}" == "class hudson.slaves.SlaveComputer" }.collect{ it.name }
    }
    else {
        SLAVES = [params.NODE]
    }
    pipeline {
        agent any
        post {
            always {
                cleanWs()
            }
        }
        stages {
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
                                    checkout scm
                                    sshagent(credentials: ['vega-ci-bot']) {
                                        sh 'sudo rm -rf /usr/local/go'
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
