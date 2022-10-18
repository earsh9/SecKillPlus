package org.example.error;

public interface CommonError {
    public int getErrCode();

    public String getErrMsg();

    //可以定制化设置错误码及信息
    public CommonError setErrMsg(String errMsg);
}
