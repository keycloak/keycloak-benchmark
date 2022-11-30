#
# AWK script for computing averages and sample standard deviations for data in CSV.
#
# It assumes that the first row in the CSV contains the column names and that
# the subsequent rows contain the data.
#
# Non-numerical values are not counted.
#
# To skip the first column in the source CSV use `-v 'FF=2'`.
#
# Computation of the sample standard deviation is equivalent to `STDEV.S` 
# funtion used in common Spreadsheet apps.
#
# Output is a CSV with computed statistical properties for each column.
#

BEGIN {
  FS = ","
  FF = (FF=="" ? 1 : FF)
}

(NR == 1) { # Print CSV header
  printf "Property"
  for(i=FF; i<=NF; i++) { 
    printf ",%s", $i
  }
}

(NR > 1) { # Collect statistical data
  for (i=FF; i<=NF; i++) {
    if ( $i ~ /^[+-]?([0-9]*[.])?[0-9]+$/ ) { # regex match for number
      n[i]++
      sum[i]+=$i
      sumsq[i]+=($i)^2
      if ( length(min[i]) == 0 || $i < min[i] ) { min[i]=$i }
      if ( length(max[i]) == 0 || $i > max[i] ) { max[i]=$i }
    }
  }
}

END { # Compute and print statistics
  for (i=FF; i<=NF; i++) {
    if ( n[i] > 0) {
      average[i] = sum[i] / n[i]
      if ( n[i] - 1 > 0 ) {
        stdevs[i] = sqrt( sumsq[i]/(n[i] - 1) - 2*average[i]*sum[i]/(n[i] - 1) + n[i]*average[i]^2/(n[i] - 1) )
        stdevs_pct[i] = (average[i] == 0) ? "N/A" : stdevs[i] / average[i]
      } else {
        stdevs[i] = "N/A"
        stdevs_pct[i] = "N/A"
      }
    }
  }
  printf "\nCOUNT:";              for (i=FF; i<=NF; i++) { printf ",%d", n[i] }
  printf "\nAVERAGE:";            for (i=FF; i<=NF; i++) { printf ",%f", average[i] }
  printf "\nSTDEV.S:";            for (i=FF; i<=NF; i++) { printf ",%f", stdevs[i] }
  printf "\nSTDEV.S / AVERAGE:";  for (i=FF; i<=NF; i++) { printf ",%f", stdevs_pct[i] }
  printf "\nAVERAGE - STDEV.S:";  for (i=FF; i<=NF; i++) { printf ",%f", average[i] - stdevs[i] }
  printf "\nAVERAGE + STDEV.S:";  for (i=FF; i<=NF; i++) { printf ",%f", average[i] + stdevs[i] }
  printf "\nMIN:";                for (i=FF; i<=NF; i++) { printf ",%f", min[i] }
  printf "\nMAX:";                for (i=FF; i<=NF; i++) { printf ",%f", max[i] }
  printf "\n"
}
