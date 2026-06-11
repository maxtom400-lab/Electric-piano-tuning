# Hardware Reference

This project was developed and tested with a small BLE DC motor controller board and a 12V geared brushed DC motor.

## Purchase Reference Links

These links are provided only as purchase/reference information. Product pages, SKU names, and seller details may change over time.

- BLE motor driver board: https://item.taobao.com/item.htm?id=583203325716&mi_id=0000pnASjkV1WvFJR3VhHBLUfNIKsbSN8rf_GA4VE3Xyocs&skuId=4581336610545&spm=tbpc.boughtlist.suborder_itemtitle.1.63db2e8dCbdL8I
- 12V geared DC motor: https://item.taobao.com/item.htm?id=1038629212708&mi_id=0000MPFRkCV8HYzLigz_w7AihiduCYEDbOQo4yfg_Wa1la4&spm=tbpc.boughtlist.suborder_itemtitle.1.254f2e8d5lmEs4&sku_properties=-3%3A-14
- Piano rib mounting bracket: https://detail.tmall.com/item.htm?id=804404331380&mi_id=0000nz7sbyz1k665It6F4N8fQaEDI2vAd_d3DbZcqMM9YCg&spm=tbpc.boughtlist.suborder_itempic.d804404331380.254f2e8d5lmEs4

## Known BLE Board Details

- BLE name: `JUXUN-88888888`
- BLE address observed during testing: `DE:AB:BD:EA:2F:DE`
- Write characteristic: `0000ffe2-0000-1000-8000-00805f9b34fb`
- Notify characteristic: `0000ffe1-0000-1000-8000-00805f9b34fb`

See `PROTOCOL.md` for the actual command bytes, speed command, stop command, heartbeat, and left/right limit switch notifications.

## Motor Notes

- Motor type: 12V geared brushed DC motor
- Approximate speed used in the app safety text: 90 RPM
- The app uses hold-to-run manual controls and guarded automatic pulses.

## Mounting Notes

- The mounting bracket is intended to clamp/fix the mechanism to a piano rib.
- Verify that the bracket cannot slide before using automatic motor pulses.
- Do not rely on software limits alone; the mechanical setup should stay stable if the phone disconnects or the motor stops late.

## Safety

Piano tuning strings can break if the motor keeps pulling in the wrong direction. Always test with a safe mechanical setup, working limit switches, and a manual stop path.
