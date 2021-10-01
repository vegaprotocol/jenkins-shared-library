import io.vegaprotocol.DockerisedVega

void call(Map config=[:]) {
    DockerisedVega dv =  new DockerisedVega()
    dv.init(config)
    return dv
}
