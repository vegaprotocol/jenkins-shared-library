// This function is used to generate list of parameters from actual environment because when Jenkins triggers a build
// it uses different java object for params than in moment when build is executed, so we have to reverse engineer actual
// params to propagate them from proxy to other jobs

// this comment is to make it easier find the method definition by using global search
// collectParams definition:
def call(blackList=[]){
    params.findAll{
        // take all params that are not dc
        !blackList.contains(it.key as String) && it.value != null && it.value != ''
    }.collect{
        if (it.value instanceof Boolean){
            booleanParam(name: it.key, value: it.value)
        }
        else if (it.value instanceof String){
            string(name: it.key, value: it.value)
        }
    }
}
