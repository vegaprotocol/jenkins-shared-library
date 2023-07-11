def call() {
    if (!params.NODE) {
        SLAVES = Jenkins.instance.computers.findAll{ "${it.class}" == "class hudson.slaves.SlaveComputer" }.collect{ it.name }
    }
    else {
        SLAVES = [params.NODE]
    }
    pipeline {
        agent any
        stages {
            stage('checkout') {
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
                    script {
                        parallel SLAVES.collectEntries { name -> [
                            (name): {
                                node(name) {
                                    checkout scm
                                    sh '''
                                        ls -al
                                        ls -al playbooks/proxmox-playbook-jenkins-agent.yaml
                                    '''
                                    sh 'ansible-playbook playbooks/proxmox-playbook-jenkins-agent.yaml'
                                }
                            }
                        ]}
                    }
                }
            }
        }
    }
}
