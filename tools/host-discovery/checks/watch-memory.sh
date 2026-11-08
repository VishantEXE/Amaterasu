#!/system/bin/sh
# Read-only, bounded high-frequency observations. Run through adb shell at nice 10.
seconds=${1:-240}
samples=$((seconds * 4))
echo 'epoch_s,available_kib,free_kib,cma_free_kib,swap_free_kib,allocstall,compact_stall,compact_fail,pgscan_direct,psi_some_total_us,psi_full_total_us'
i=0
while [ "$i" -lt "$samples" ]; do
    printf '%s,' "$(date +%s.%N)"
    awk '
        $1=="MemAvailable:" {available=$2}
        $1=="MemFree:" {free=$2}
        $1=="CmaFree:" {cma=$2}
        $1=="SwapFree:" {swap=$2}
        $1 ~ /^allocstall/ {allocstall+=$2}
        $1=="compact_stall" {compact=$2}
        $1=="compact_fail" {fail=$2}
        $1 ~ /^pgscan_direct/ {scan+=$2}
        $1=="some" {split($5,a,"=");some=a[2]}
        $1=="full" {split($5,a,"=");full=a[2]}
        END {printf "%d,%d,%d,%d,%d,%d,%d,%d,%s,%s\n",available,free,cma,swap,allocstall,compact,fail,scan,some,full}
    ' /proc/meminfo /proc/vmstat /proc/pressure/memory
    sleep 0.25
    i=$((i + 1))
done
