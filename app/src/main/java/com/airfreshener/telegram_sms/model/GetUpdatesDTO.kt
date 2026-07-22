package com.airfreshener.telegram_sms.model

class GetUpdatesDTO {

    @JvmField
    var offset: Long = 0

    @JvmField
    var timeout = 0

    @JvmField
    var limit = 20

}
