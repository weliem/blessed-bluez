#!/bin/sh
chmod 777 /sys/kernel/debug
chmod 666 /sys/kernel/debug/bluetooth/hci0/conn_min_interval
chmod 666 /sys/kernel/debug/bluetooth/hci0/conn_max_interval
chmod 666 /sys/kernel/debug/bluetooth/hci0/supervision_timeout
echo 6 > /sys/kernel/debug/bluetooth/hci0/conn_min_interval
echo 24 > /sys/kernel/debug/bluetooth/hci0/conn_max_interval
echo 200 > /sys/kernel/debug/bluetooth/hci0/supervision_timeout


