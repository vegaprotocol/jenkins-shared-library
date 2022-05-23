def call(String credentialsId, Closure body) {
    withCredentials([file(credentialsId: credentialsId, variable: 'GC_KEY')]) {
        sh """
            gcloud auth activate-service-account --key-file=${GC_KEY}
            gcloud container clusters get-credentials gke-01 --region europe-west1 --project vegaprotocol
        """
        body()
    }
}