#include "fstapi.h"
#include <cstring>
#include <initializer_list>
int main(int argc,char **argv) {
  if(argc!=3)return 2;
  bool flat=!strcmp(argv[2],"flat"),jump=!strcmp(argv[2],"jump"),missing=!strcmp(argv[2],"missing");
  auto*w=fstWriterCreate(argv[1],1);
  for(auto name:{"TOP","traceMod","ram","core"})fstWriterSetScope(w,FST_ST_VCD_MODULE,name,nullptr);
  auto reset=fstWriterCreateVar(w,FST_VT_VCD_WIRE,FST_VD_INPUT,1,"reset",0);
  fstWriterSetScope(w,FST_ST_VCD_MODULE,"memory_ext",nullptr);
  auto clk=fstWriterCreateVar(w,FST_VT_VCD_WIRE,FST_VD_INPUT,1,"W0_clk",0);
  auto en=fstWriterCreateVar(w,FST_VT_VCD_WIRE,FST_VD_INPUT,1,"W0_en",0);
  auto addr=fstWriterCreateVar(w,FST_VT_VCD_WIRE,FST_VD_INPUT,8,"W0_addr",0);
  auto data=fstWriterCreateVar(w,FST_VT_VCD_WIRE,FST_VD_INPUT,32,missing?"absent_data":"W0_data",0);
  fstWriterEmitTimeChange(w,0);
  fstWriterEmitValueChange32(w,clk,1,flat?1:0);fstWriterEmitValueChange32(w,reset,1,1);
  fstWriterEmitValueChange32(w,en,1,0);fstWriterEmitValueChange32(w,addr,8,0);fstWriterEmitValueChange32(w,data,32,0);
  fstWriterEmitTimeChange(w,1);fstWriterEmitValueChange32(w,clk,1,1);
  fstWriterEmitTimeChange(w,2);fstWriterEmitValueChange32(w,clk,1,flat?1:0);
  fstWriterEmitValueChange32(w,reset,1,0);fstWriterEmitValueChange32(w,en,1,1);
  fstWriterEmitValueChange32(w,data,32,0x11111111);
  for(unsigned i=0;i<300;++i) {
    fstWriterEmitTimeChange(w,3+2*i);fstWriterEmitValueChange32(w,clk,1,1);
    fstWriterEmitValueChange32(w,addr,8,(jump&&i==149)?5:((i+1)&255));
    fstWriterEmitValueChange32(w,data,32,i==299?0x33333333:0x22222222);
    if(i==299)fstWriterEmitValueChange32(w,en,1,0);
    fstWriterEmitTimeChange(w,4+2*i);fstWriterEmitValueChange32(w,clk,1,flat?1:0);
  }
  fstWriterEmitTimeChange(w,999);fstWriterEmitValueChange32(w,clk,1,1);
  fstWriterClose(w);return 0;
}
