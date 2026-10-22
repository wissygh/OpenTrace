#include "fstapi.h"
#include <array>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <map>
#include <string>
#include <vector>

// Sample the physical SRAM write port using its values before each rising edge.
// The FST must contain both clock edges (--dump-wave-full).
struct Values { std::array<uint64_t,5> v{}; unsigned known=0; } current, before;
static std::map<fstHandle,std::vector<unsigned>> indices;
static uint64_t timeNow=0, words=0, edges=0;
static bool haveTime=false, failed=false;
static FILE *raw, *rows;
static void finishTime() {
  if (!haveTime || before.known != 31 || current.known != 31) return;
  if (!before.v[0] && current.v[0]) {
    ++edges;
    if (!before.v[1] && before.v[2]) {
      if (before.v[3] != (words & 255)) {
        fprintf(stderr,"nonconsecutive SRAM write at time %llu: addr=%llu expected=%llu\n",
          (unsigned long long)timeNow,(unsigned long long)before.v[3],(unsigned long long)(words&255));
        failed=true; return;
      }
      uint32_t value=before.v[4];
      unsigned char bytes[4];for(unsigned i=0;i<4;++i)bytes[i]=(value>>(i*8))&255;
      fwrite(bytes,1,4,raw);
      fprintf(rows,"%llu\t%llu\t%llu\t0x%08x\n",(unsigned long long)words,
        (unsigned long long)timeNow,(unsigned long long)before.v[3],value);
      ++words;
    }
  }
}
static void change(void*, uint64_t t, fstHandle h, const unsigned char *v) {
  if (failed) return;
  if (!haveTime || t != timeNow) {
    finishTime();before=current;timeNow=t;haveTime=true;
  }
  for(unsigned i:indices[h]) {
    if (strspn((const char*)v,"01") != strlen((const char*)v)) {
      fprintf(stderr,"unknown value on selected SRAM signal\n"); failed=true;return;
    }
    current.v[i]=strtoull((const char*)v,nullptr,2);current.known|=1u<<i;
  }
}
int main(int argc,char **argv) {
  if(argc!=5) {fprintf(stderr,"usage: read-sram FST RAW TSV SIGNALS\n");return 2;}
  auto *reader=fstReaderOpen(argv[1]);if(!reader)return 3;
  raw=fopen(argv[2],"wb"); rows=fopen(argv[3],"w");FILE *signals=fopen(argv[4],"w");
  if(!raw||!rows||!signals)return 4;
  fprintf(rows,"word_index\tfst_time\tword_address\tdata\n");
  fstReaderClrFacProcessMaskAll(reader);
  std::vector<std::string> scopes;
  unsigned found=0;fstHier *h;
  const char *suffixes[]={".traceMod.ram.core.memory_ext.W0_clk",
    ".traceMod.ram.core.reset", ".traceMod.ram.core.memory_ext.W0_en",
    ".traceMod.ram.core.memory_ext.W0_addr", ".traceMod.ram.core.memory_ext.W0_data"};
  while((h=fstReaderIterateHier(reader))) {
    if(h->htyp==FST_HT_SCOPE)scopes.push_back(h->u.scope.name);
    else if(h->htyp==FST_HT_UPSCOPE)scopes.pop_back();
    else if(h->htyp==FST_HT_VAR) {
      std::string path;for(auto&s:scopes)path+=s+".";
      std::string name=h->u.var.name;auto p=name.find(' ');if(p!=std::string::npos)name.resize(p);
      path+=name;
      for(unsigned i=0;i<5;++i) {
        std::string end=suffixes[i];
        if(path.size()>=end.size()&&path.compare(path.size()-end.size(),end.size(),end)==0) {
          indices[h->u.var.handle].push_back(i);found|=1u<<i;
          fstReaderSetFacProcessMask(reader,h->u.var.handle);
          fprintf(signals,"%u\t%u\t%s\n",i,h->u.var.handle,path.c_str());
        }
      }
    }
  }
  fclose(signals);
  if(found!=31) {fprintf(stderr,"missing SRAM signals: found mask=%u\n",found);return 5;}
  fstReaderIterBlocks(reader,change,nullptr,nullptr);finishTime();
  fprintf(stderr,"rising_edges=%llu words=%llu bytes=%llu end_time=%llu\n",
    (unsigned long long)edges,(unsigned long long)words,(unsigned long long)(words*4),
    (unsigned long long)fstReaderGetEndTime(reader));
  fstReaderClose(reader);fclose(raw);fclose(rows);
  return failed||!edges||!words ? 6 : 0;
}
