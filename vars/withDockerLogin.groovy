def call(String credentialsId, boolean logout, Closure body) {
    withCredentials([
        usernamePassword(credentialsId: credentialsId, passwordVariable: 'DOCKER_PASSWORD', usernameVariable:'DOCKER_USERNAME')
    ]) {
        sh label: 'docker login to dockerhub', script: '''
            echo "''' + DOCKER_PASSWORD + '''" | docker login --username ''' + DOCKER_USERNAME + ''' --password-stdin
        '''

        body()
        
        if (logout) {
            sh label: 'docker logout from dockerhub', script: 'docker logout'
        }
    }
}