TARGET_VERSION=1.8

# Change these to the proper locations
BOOTCLASSPATH=~/Library/Java/JavaVirtualMachines/temurin-1.8.0_402/Contents/Home/jre/lib/rt.jar
IJCLASSPATH=/Applications/ImageJ.app/Contents/Java/ij.jar
INSTALL_DIR=/Applications/ImageJ.app/plugins

PLUGINS_DIR=plugins
KMAX_DIR=$(PLUGINS_DIR)/Kmax
CRYSTALMAPPER_DIR=$(PLUGINS_DIR)/CrystalMap

# Find all Java source files
SOURCES=$(wildcard $(KMAX_DIR)/*.java $(CRYSTALMAPPER_DIR)/*.java)

# Default target
all: compile

# Compile Java files
compile:
	@echo "Compiling Java files..."
	javac -source $(TARGET_VERSION) -target $(TARGET_VERSION) -bootclasspath $(BOOTCLASSPATH) -cp $(IJCLASSPATH):. $(SOURCES)
	@echo "Compilation finished."

# Clean up .class files
clean:
	@echo "Cleaning up..."
	rm -f $(KMAX_DIR)/*.class $(CRYSTALMAPPER_DIR)/*.class
	@echo "Clean finished."

install:
	@echo "Installing plugins..."
	cp -R $(PLUGINS_DIR)/* $(INSTALL_DIR)

.PHONY: all compile clean

