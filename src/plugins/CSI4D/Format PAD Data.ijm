width=256; height=256;
Dialog.create("Format PAD data...");
Dialog.addNumber("Width:", 256);
Dialog.addNumber("Height:", 256);
Dialog.show();
width = Dialog.getNumber();
height = Dialog.getNumber();
makeRectangle(2, 2, 124, 124);
run("Crop");
run("Stack to Hyperstack...", "order=xyczt(default) channels=1 slices="+width+" frames="+height+" display=Grayscale");
resetMinAndMax();