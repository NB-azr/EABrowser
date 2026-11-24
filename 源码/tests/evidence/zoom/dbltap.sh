#!/system/bin/sh
# 双击 (540,900)：两次按下间隔尽量短（双击窗口 300ms 从第一次按下算起）
dev=/dev/input/event1

sendevent $dev 3 57 8
sendevent $dev 3 53 16384
sendevent $dev 3 54 15360
sendevent $dev 1 330 1
sendevent $dev 0 0 0
sendevent $dev 3 57 -1
sendevent $dev 1 330 0
sendevent $dev 0 0 0
sleep 0.02
sendevent $dev 3 57 9
sendevent $dev 3 53 16384
sendevent $dev 3 54 15360
sendevent $dev 1 330 1
sendevent $dev 0 0 0
sendevent $dev 3 57 -1
sendevent $dev 1 330 0
sendevent $dev 0 0 0
