clean:
	rm -rf ./build

init:
	git submodule update --init
	cd rocket-chip && git submodule update --init cde hardfloat

bump:
	git submodule foreach "git fetch origin&&git checkout master&&git reset --hard origin/master"

bsp:
	mill -i mill.bsp.BSP/install

idea:
	mill -i mill.idea.GenIdea/idea
