package com.frejun.androidsoftphonesdk.exceptions

class SdkNotInitializedException :
    IllegalStateException("SoftphoneSDK has not been initialized. Please call SoftphoneSDK.initialize() in your Application class.")