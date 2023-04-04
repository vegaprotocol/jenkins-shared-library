
void call(Map config = [:], Closure body=null) {
    String credentialsId = config.get('credentialsId', vegautils.getVegaCiBotCredentials())
    if (credentialsId && body) {
        try {
            withCredentials([usernamePassword(credentialsId: credentialsId, passwordVariable: 'TOKEN', usernameVariable:'USER')]) {
                // Workaround for user input:
                //  - global configuration: 'gh config set prompt disabled'
                sh label: 'Log in to a Gihub with CI', script: '''
                    echo ${TOKEN} | gh auth login --with-token -h github.com
                '''
            }
            body()
        } finally {
            retry(3) {
                script {
                    sh label: 'Log out from Github',
                        returnStatus: true,  // ignore exit code
                        script: '''
                            gh auth logout -h github.com
                        '''
                }
            }
        }
    }
}
