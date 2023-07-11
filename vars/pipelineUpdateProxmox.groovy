def call() {
    if (!params.NODE) {
        SLAVES = Jenkins.instance.computers.findAll{ "${it.class}" == "class hudson.slaves.SlaveComputer" }.collect{ it.name }
    }
    else {
        SLAVES = [params.NODE]
    }
    pipeline {
        agent none
        stages {
            stage('checkout') {
                agent any
                steps {
                    checkout scm
                }
            }
            stage('trigger provisioner') {
                when {
                    anyOf {
                        changeset "roles/jenkins-agent/**"
                        changeset "playbooks/proxmox-playbook-jenkins-agent.yaml"
                        triggeredBy 'UserIdCause'
                    }
                }
                steps {
                    parallel SLAVES.collectEntries { name -> [
                        (name): {
                            node(name) {
                                checkout scm
                                sh 'ansible-playbook playbooks/proxmox-playbook-jenkins-agent.yaml'
                            }
                        }
                    ]}
                }
            }
        }
    }
}
