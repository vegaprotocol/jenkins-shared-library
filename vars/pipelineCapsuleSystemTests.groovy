void call() {
  println('pipelineCapsuleSystemTests params: ' + params)
  pipeline {
    agent none
    stages {
      stage('Call tests') {
        build(
          job: 'common/system-tests-wrapper',
          parameters: collectParams(),
        )
      }
    }
  }
}

