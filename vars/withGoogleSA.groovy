def call(String credentialsId, Closure body) {
    withCredentials([file(credentialsId: credentialsId, variable: 'GC_KEY')]) {
        sh("gcloud auth activate-service-account --key-file=${GC_KEY}")
        body()
    }
}