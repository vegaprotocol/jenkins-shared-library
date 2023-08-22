def call() {
    pipeline {
        agent {
            label params.NODE_LABEL
        }
        environment {
            PERFHOME = "${env.WORKSPACE}/performance"
            PATH = "${env.PATH}:${env.PERFHOME}/bin"
            POSTGRES_HOST = "jenkins-performance-do-user-11836577-0.b.db.ondigitalocean.com"
            POSTGRES_PORT = "25060"
            POSTGRES_USER = "doadmin"
            POSTGRES_DB = "defaultdb"
            PGPASSWORD = credentials("PERFORMANCE_DB_PASSWORD")
            GOBIN = "${env.WORKSPACE}/gobin"
        }
        options {
              timeout(time: 6, unit: 'HOURS')
        }
        stages {
            stage('get source codes') {
                steps {
                    sh 'mkdir -p bin'
                    script {
                        vegautils.commonCleanup()
                        def repositories = [
                            [ name: 'vegaprotocol/vega', branch: params.VEGA_BRANCH, install: true ],
                            [ name: 'vegaprotocol/vegacapsule', branch: params.VEGACAPSULE_BRANCH, install: true ],
                            [ name: 'vegaprotocol/vegatools', branch: params.VEGATOOLS_BRANCH, install: true ],
                            [ name: 'vegaprotocol/performance', branch: params.PERFORMANCE_BRANCH ],
                        ]
                        def reposSteps = repositories.collectEntries{value -> [
                            value.name,
                            {
                                def directory = value.name.split('/')[1]
                                gitClone([
                                    url: 'git@github.com:' + value.name + '.git',
                                    branch: value.branch,
                                    directory: directory,
                                    credentialsId: 'vega-ci-bot',
                                    timeout: 2,
                                ])
                                if (value.install) {
                                    dir(directory) {
                                        sh 'go install ./...'
                                    }
                                }
                            }
                        ]}
                        parallel reposSteps
                    }
                    sh '''
                        mv vega performance
                        mv vegatools performance
                        mv vegacapsule performance
                    '''
                }
            }
            stage('prerequisities') {
                steps {
                    sh '''
                        pg_isready -d ${POSTGRES_DB} -h ${POSTGRES_HOST} -p ${POSTGRES_PORT} -U ${POSTGRES_USER}
                    '''
                    dir('performance') {
                        sh '''
                            bash -ex prerequisites.sh --skip-clone
                        '''
                    }
                }
            }
            stage('performance') {
                steps {
                    dir('performance') {
                        sh '''
                            bash -ex runtests.sh --pprof-collection
                        '''
                    }
                }
            }
        }
        post {
            always {
                archiveArtifacts(
                    artifacts: 'performance/pprofs/**',
                    allowEmptyArchive: true,
                )
                archiveArtifacts(
                    artifacts: 'performance/results.sql',
                    allowEmptyArchive: true,
                )
                archiveArtifacts(
                    artifacts: 'performance/logs/**',
                    allowEmptyArchive: true,
                )
                cleanWs()
            }
        }
    }
}
