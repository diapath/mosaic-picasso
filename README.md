# QuPath integration

You can follow the discussion on the image.sc forum: https://forum.image.sc/t/using-qupath-for-spectral-unmixing/55376/18

To enable the integration, you will first need to create an appropriate conda environment with:

```
conda env create -f https://raw.githubusercontent.com/diapath/mosaic-picasso/refs/heads/main/qupath-picasso.yml
```

This uses the [yml file](/qupath-picasso.yml) from this repository.

Then you will need to copy the two script files  in the [qupath folder]((qupath/)) ([run=mosaic.py](/qupath/run_mosaic.py) and [run=mosaic.groovy](/qupath/run_mosaic.groovy)) into your QuPath project's scripts folder.

You will also probably need to change the `condaExe` variable in `run_picasso.groovy` to match your conda / miniconda / anaconda installation.

As explained on the image.sc thread, there are 4 steps in the groovy script which can individually be enabled or disabled with the appropriate flags at the top of the script:

* `do_step0` : This step if enabled saves the current image as an ome.tiff. An additional flag (`save_crop`) controls whether to save the unmixing selected region as a cropped image (takes a few seconds) or save the whole image (which takes longer) but can then be used for other purposes, such as VALIS image alignment.
* `do_step1` : This step creates a JSON file with crop info and channel data, which are then used by the Python script.
* `do_step2` : This step launches the Python script via `conda run` in the right environment (qupath-picasso). The Python script updates the input JSON file with the linear coefficients computed by mosaic-PICASSO (matrix_p)
* `do_step3` : This step loads the JSON file and creates a new project entry for the linearly unmixed image. Intensity ranges for each channel are set using the 2 and 98 percentiles calculated on the Python side (on the output image crop). We could think of optimising each output channel linear combination by zero-ing any coefficient < err * max_row_value (say for example err=1e-3).

# mosaic-picasso
More information can be found at: https://academic.oup.com/bioinformatics/article/40/1/btad784/7510840 and https://www.biorxiv.org/content/10.1101/2023.07.06.547878v1 

Please cite as:
Hu Cang, Yang Liu, Jianhua Xing, Mosaic-PICASSO: accurate crosstalk removal for multiplex fluorescence imaging, Bioinformatics, Volume 40, Issue 1, January 2024, btad784, https://doi.org/10.1093/bioinformatics/btad784
