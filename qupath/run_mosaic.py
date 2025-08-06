import mosaic_picasso.mosaic as mp
import mosaic_picasso.utils as utils
import zarr
import tifffile
import json
import numpy as np
import sys
import os

def demux(fn_image, fn_json=None):
    """Takes a OME-TIFF image, applies the pipeline, saves the data back into the json file

    The idea is that you can either provide an OME-TIFF and crop coordinates in the json file
    or, no fn_json and whatever info will be saved in a json file derived from fn_image
    """

    # Load the image as zarr
    store = tifffile.imread(fn_image, aszarr=True)
    z = zarr.open(store, mode='r')
    #print("zarr type", type(z))
    #print("zarr keys:", z.keys())
    first_key = list(z.keys())[0]


    dic = {
        'bins' : 256,
        'cycles' : 40,
        'beta' : 0,
        'gamma' : 0.1,
        'mode': 'ssim',
        'nch' : z[first_key].shape[2],
    }

    # Default is the image name followed by the .umxjson extension
    if fn_json is None:
        fn_json = fn_image + ".umxjson"

    if os.path.exists(fn_json):
        with open(fn_json) as f:
            data = json.load(f)
    else:
        data = {}

    if 'crop' in data.keys():
        x1 = data['crop']['x']
        x2 = data['crop']['x']+data['crop']['width']
        y1 = data['crop']['y']
        y2 = data['crop']['y']+data['crop']['height']
        im = z[first_key][y1:y2,x1:x2,:].astype(np.float64)
    else:
        # use the whole image. hopefully, a cropped region?
        im = np.array(z[first_key], dtype=np.float64)  # creates a writable NumPy array


    # Additional variables we may have added to the json:
    for k in ['bins', 'beta', 'gamma', 'cycles', 'mode']:
        if k in data.keys():
            dic[k] = data[k]

    # pre processing
    im_drift_corrected = utils.drift_corr(im)
    im_drift_corrected_bg_removed = utils.bg_remove(im_drift_corrected)

    # mosaic processing
    mosaic = mp.MosaicPicasso(**dic)
    im_mosaic, P = mosaic.mosaic(im_drift_corrected_bg_removed)

    # the linear unmixing coefficients
    data['p_matrix'] = P.tolist()


    # intensity ranges based on percentiles
    data['output_channel_ranges'] = [
        [np.percentile(im[:,:,i], 2), np.percentile(im[:,:,i], 98)] for i in range(dic['nch'])
    ]

    # Write to JSON
    with open(fn_json, 'w') as f:
        json.dump(data, f, indent=4)

if __name__ == '__main__':
    #Here the idea is that you can just pass an image filename and demux will try to find a json if available
    print(sys.argv)

    fn_image = sys.argv[1]
    fn_json = sys.argv[2] if len(sys.argv) > 2 else None
    demux(fn_image, fn_json)
