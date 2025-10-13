package com.madgag.micropython.logiccapture.worker.serialport

import com.fazecast.jSerialComm.SerialPort

case class UsbId(vendorId: Int, productId: Int)

object UsbId {
  def parse(vendorAndProductId: String): UsbId = {
    val ints = vendorAndProductId.split(':').map(Integer.decode)
    UsbId(ints(0), ints(1))
  }
}

extension (sp: SerialPort)
  def usbId: Option[UsbId] = Option.when(sp.getVendorID != -1 && sp.getProductID != -1)(
    UsbId(sp.getVendorID, sp.getProductID)
  )
