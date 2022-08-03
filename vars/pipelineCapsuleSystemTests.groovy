void call() {
  println('pipelineCapsuleSystemTests params: ' + params)
  pipeline {
    agent none
    stages {
      stage('Call tests') {
        steps {
          build(
            job: 'common/system-tests-wrapper',
            parameters: collectParams(),
          )
        }
      }
    }
  }
}

