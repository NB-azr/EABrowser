#!/system/bin/sh
# 双指捏合 2x：两指中点 (540,900)，间距 200px -> 400px（分 8 步，每步 25px）
# 坐标换算：32767/1080 = 30.34，32767/1920 = 17.066
dev=/dev/input/event1

sendevent $dev 3 47 0
sendevent $dev 3 57 1
sendevent $dev 3 53 13350
sendevent $dev 3 54 15360
sendevent $dev 3 48 8
sendevent $dev 3 58 500
sendevent $dev 3 47 1
sendevent $dev 3 57 2
sendevent $dev 3 53 19418
sendevent $dev 3 54 15360
sendevent $dev 3 48 8
sendevent $dev 3 58 500
sendevent $dev 1 330 1
sendevent $dev 0 0 0
sleep 0.05

sendevent $dev 3 47 0
sendevent $dev 3 53 12970
sendevent $dev 3 47 1
sendevent $dev 3 53 19795
sendevent $dev 0 0 0
sleep 0.12

sendevent $dev 3 47 0
sendevent $dev 3 53 12590
sendevent $dev 3 47 1
sendevent $dev 3 53 20175
sendevent $dev 0 0 0
sleep 0.12

sendevent $dev 3 47 0
sendevent $dev 3 53 12210
sendevent $dev 3 47 1
sendevent $dev 3 53 20555
sendevent $dev 0 0 0
sleep 0.12

sendevent $dev 3 47 0
sendevent $dev 3 53 11830
sendevent $dev 3 47 1
sendevent $dev 3 53 20936
sendevent $dev 0 0 0
sleep 0.12

sendevent $dev 3 47 0
sendevent $dev 3 53 11450
sendevent $dev 3 47 1
sendevent $dev 3 53 21316
sendevent $dev 0 0 0
sleep 0.12

sendevent $dev 3 47 0
sendevent $dev 3 53 11070
sendevent $dev 3 47 1
sendevent $dev 3 53 21696
sendevent $dev 0 0 0
sleep 0.12

sendevent $dev 3 47 0
sendevent $dev 3 53 10690
sendevent $dev 3 47 1
sendevent $dev 3 53 22076
sendevent $dev 0 0 0
sleep 0.12

sendevent $dev 3 47 0
sendevent $dev 3 53 10316
sendevent $dev 3 47 1
sendevent $dev 3 53 22452
sendevent $dev 0 0 0
sleep 0.15

sendevent $dev 3 47 0
sendevent $dev 3 57 -1
sendevent $dev 3 47 1
sendevent $dev 3 57 -1
sendevent $dev 1 330 0
sendevent $dev 0 0 0
