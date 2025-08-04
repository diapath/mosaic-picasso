// **** Combined Unmixing Workflow Script for QuPath 0.6.0 ****
// Combines: OME-TIFF export, crop export, and 3-step unmixing process


//  ****** CONTROL PARAMETERS *********
def save_crop = true        // Set to true to save cropped region instead of full image
def do_step0 = true         // Save OME-TIFF (full or cropped)
def do_step1 = true         // Create JSON with crop info and channel data
def do_step2 = true         // Run Python unmixing script
def do_step3 = true         // Import unmixed results back to QuPath

//  ****** OME.TIFF PARAMETERS *********
def tilesize = 512
def outputDownsample = 1
def pyramidscaling = 4       // Use 2 for crops, 4 for full images
def nThreads = 8
def compression = OMEPyramidWriter.CompressionType.UNCOMPRESSED

//  ****** UNMIXING PARAMETERS *********
def regionClass = "unmixing"

//  ****** PYTHON ENVIRONMENT PARAMETERS *********
def condaEnv = "qupath-picasso"  // name of the conda environment
def condaExe = "c:\\ProgramData\\miniconda3\\condabin\\conda.bat"
//def condaExe = "C:\\Users\\diapath\\miniforge3\\condabin\\conda.bat"
def pythonScript = buildFilePath(PROJECT_BASE_DIR, "scripts", "run_mosaic.py")

//  ****** MOSAIC-PICASSO PARAMETERS *********
def bins = 256              // Number of bins for histogram analysis
def cycles = 40             // Number of optimization cycles
def beta = 0                // Beta parameter for regularization
def gamma = 0.1             // Gamma parameter for regularization
def mode = 'ssim'           // Similarity metric: 'ssim' (structural similarity index), 'pearson', or 'mi' (mutual information)

//  ***************************

// Logger
def logger = LoggerFactory.getLogger(this.class)

// Get basic image data
def imageData = getCurrentImageData()
def server = imageData.getServer()
def name = getProjectEntry().getImageName()
def cal = server.getPixelCalibration()
def pixelWidth = cal.getPixelWidth()
def pixelHeight = cal.getPixelHeight()

logger.info("=== Starting Combined Unmixing Workflow ===")
logger.debug("Image: ${name}")
logger.debug("Control settings: save_crop=${save_crop}, do_step0=${do_step0}, do_step1=${do_step1}, do_step2=${do_step2}, do_step3=${do_step3}")
logger.debug("Mosaic-PICASSO settings: bins=${bins}, cycles=${cycles}, beta=${beta}, gamma=${gamma}, mode=${mode}")

// Define JSON structure for step 1
class UnmixingData {
    @SerializedName("p_matrix")    
    Double[][] p_matrix
    @SerializedName("output_channel_ranges")    
    Double[][] output_channel_ranges
}

// Function to convert int color to #rrggbbaa
def toHexColorWithAlpha = { int packedRGB ->
    def r = ColorTools.red(packedRGB)
    def g = ColorTools.green(packedRGB)
    def b = ColorTools.blue(packedRGB)
    def a = 255 // always opaque
    return String.format("#%02x%02x%02x%02x", r, g, b, a)
}

// =========================
// STEP 0: Save OME-TIFF
// =========================
if (do_step0) {
    logger.info("=== STEP 0: Saving OME-TIFF ===")
    
    def serverToExport = server
    def pathOutput
    
    if (save_crop) {
        logger.debug("Looking for crop region...")
        
        // Find rectangular annotation or type "unmixing"
        def annotations = getAnnotationObjects()
        def matchingAnnotations = annotations.findAll { a ->
            def roi = a.getROI()
            return (roi instanceof RectangleROI || a.getPathClass()?.toString()?.toLowerCase() == regionClass)
        }
        
        if (matchingAnnotations.isEmpty()) {
            logger.error("No rectangular or 'unmixing' annotations found for cropping!")
            return
        } else if (matchingAnnotations.size() > 1) {
            logger.warn("More than one matching annotation found. Using the first one.")
        }
        
        def selectedAnnotation = matchingAnnotations[0]
        def region = ImageRegion.createInstance(selectedAnnotation.getROI())
        
        // Create cropped image server
        serverToExport = new CroppedImageServer(server, region)
        pathOutput = GeneralTools.toPath(server.getURIs()[0]).toString() + ".umxcrop.ome.tif"
        
        // Use smaller pyramid scaling for crops
        pyramidscaling = 2
        
        logger.debug("Cropping to region: ${region}")
    } else {
        pathOutput = GeneralTools.toPath(server.getURIs()[0]).toString() + ".ome.tif"
    }
    
    logger.info("Writing OME-TIFF: ${pathOutput}")
    
    new OMEPyramidWriter.Builder(serverToExport)
        .compression(compression)
        .parallelize(nThreads)
        .channelsInterleaved()
        .tileSize(tilesize)
        .scaledDownsampling(outputDownsample, pyramidscaling)
        .build()
        .writePyramid(pathOutput)
    
    logger.info("Step 0 completed: OME-TIFF saved")
}

// =========================
// STEP 1: Create JSON
// =========================
if (do_step1) {
    logger.info("=== STEP 1: Creating JSON metadata ===")
    
    // Get channel information
    def channels = server.getMetadata().getChannels()
    def channelNames = channels.collect { it.getName() }
    def hexColors = channels.collect { toHexColorWithAlpha(it.getColor()) }
    
    logger.debug("Channel names: $channelNames")
    logger.debug("Channel colors: $hexColors")
    
    // Create JSON file
    def pathJson = GeneralTools.toPath(server.getURIs()[0]).toString() + ".ome.tif.umxjson"
    logger.debug("Writing JSON: ${pathJson}")
    
    def gson = new GsonBuilder().setPrettyPrinting().create()
    
    // Prepare JSON data
    def jsonData = [
        input_channel_names: channelNames,
        input_channel_colors: hexColors,
        // Mosaic-PICASSO parameters
        bins: bins,
        cycles: cycles,
        beta: beta,
        gamma: gamma,
        mode: mode
    ]
    
    // Only include crop coordinates if we're NOT saving a cropped image
    // (because the Python script will use the whole image when no crop is specified)
    if (!save_crop) {
        // Find unmixing annotation for crop coordinates
        def annotations = getAnnotationObjects().findAll {it.getPathClass() == getPathClass(regionClass)}
        
        if (annotations.isEmpty()) {
            logger.error("No 'unmixing' annotations found!")
            return
        } else if (annotations.size() > 1) {
            logger.warn("More than one unmixing annotation found. Using the first one.")
        }
        
        def roi = annotations[0].getROI()
        
        int xPx = roi.getBoundsX()
        int yPx = roi.getBoundsY()
        int wPx = roi.getBoundsWidth()
        int hPx = roi.getBoundsHeight()
        
        logger.debug("Including crop region (pixels): x=${xPx}, y=${yPx}, width=${wPx}, height=${hPx}")
        
        def crop = [
            x     : xPx,
            y     : yPx,
            width : wPx,
            height: hPx
        ]
        jsonData.crop = crop
    } else {
        logger.info("Saving cropped image - no crop coordinates needed in JSON")
    }
    
    def writer = Files.newBufferedWriter(Paths.get(pathJson))
    gson.toJson(jsonData, writer)
    writer.close()
    
    logger.info("Step 1 completed: JSON metadata saved")
}

// =========================
// STEP 2: Run Python script
// =========================
if (do_step2) {
    logger.info("\n=== STEP 2: Running Python unmixing script ===")
    
    def pathImage = GeneralTools.toPath(server.getURIs()[0]).toString()
    if (save_crop) pathImage = pathImage + ".umxcrop"
    pathImage = pathImage + ".ome.tif"
    
    def pathJson = GeneralTools.toPath(server.getURIs()[0]).toString() + ".ome.tif.umxjson"
    
    logger.debug("Image path: ${pathImage}")
    logger.debug("JSON path: ${pathJson}")
    logger.debug("Python script: ${pythonScript}")
    logger.debug("Conda environment: ${condaEnv}")
    
    def command = [
        condaExe, "run",
        "-n", condaEnv,
        "python", pythonScript,
        pathImage,
        pathJson
    ]
    
    logger.debug("Executing command: ${command.join(' ')}")
    
    def process = new ProcessBuilder(command)
        .redirectErrorStream(true)
        .start()
    
    def output = new StringBuilder()
    process.inputStream.eachLine { 
        def line = it.toString()
        logger.info("Python: ${line}")
        output.append(line).append('\n') 
    }
    def exitCode = process.waitFor()
    
    if (exitCode == 0) {
        logger.info("Step 2 completed: Python script executed successfully")
    } else {
        logger.error("Python script failed with exit code ${exitCode}")
        logger.error("Output: ${output.toString()}")
        return
    }
}

// =========================
// STEP 3: Import results
// =========================
if (do_step3) {
    logger.info("=== STEP 3: Importing unmixed results ===")
    
    def pathJson = GeneralTools.toPath(server.getURIs()[0]).toString() + ".ome.tif.umxjson"
    logger.debug("Reading results from: ${pathJson}")
    
    // Load and parse JSON
    def jsonFile = new File(pathJson)
    if (!jsonFile.exists()) {
        logger.error("JSON file not found: ${pathJson}")
        return
    }
    
    def jsonText = jsonFile.text
    def gson = new Gson()
    def unmixing = gson.fromJson(jsonText, UnmixingData.class)
    
    if (unmixing.p_matrix == null) {
        logger.error("No unmixing matrix found in JSON")
        return
    }
    
    double[][] unmixingMatrix = unmixing.p_matrix
    logger.debug("Loaded unmixing matrix: ${unmixingMatrix.length} output channels")
    
    // Get original channel info
    def channels = server.getMetadata().getChannels()
    def channelNames = channels.collect { it.getName() }
    def channelColors = channels.collect { it.getColor() }
    
    // Build transforms from matrix
    def transforms = []
    for (int i = 0; i < unmixingMatrix.length; i++) {
        def weights = unmixingMatrix[i] as double[]
        def transform = ColorTransforms.createLinearCombinationChannelTransform(weights)
        transforms.add(transform)
    }
    
    // Create transformed server
    def imageType = imageData.getImageType()
    def serverOriginal = imageData.getServer()
    
    def unmixedServer = new TransformedServerBuilder(serverOriginal)
        .applyColorTransforms(transforms)
        .build()
    
    // Add unmixed server to project
    def project = getProject()
    if (project == null) {
        logger.error("No project open!")
        return
    }
    
    def entry = ProjectCommands.addSingleImageToProject(project, unmixedServer, imageType)
    if (entry == null) {
        logger.error("Failed to add image to project!")
        return
    }
    
    // Name the new entry
    def newEntryName = "Unmixed_" + serverOriginal.getMetadata().getName()
    entry.setImageName(newEntryName)
    def unmixedImageData = entry.readImageData()
    
    // Set entry channel names
    if (channelNames != null && !channelNames.isEmpty()) {
        setChannelNames(unmixedImageData, *channelNames)
    }
    
    // Set entry channel colors
    if (channelColors != null && !channelColors.isEmpty()) {
        setChannelColors(unmixedImageData, *channelColors)
    }
    
    // Set entry channel ranges if available
    if (unmixing.output_channel_ranges != null) {
        for (int i = 0; i < unmixing.output_channel_ranges.length; i++) {
            def cr = unmixing.output_channel_ranges[i]
            setChannelDisplayRange(unmixedImageData, i, cr[0], cr[1])
        }
    }

    // Here we recompute the entry thumbnail with color ranges applied
    // https://forum.image.sc/t/creating-new-imageserver-object-resulting-from-pixelwise-operation-between-channels/115199/8
    def imgThumbnail = unmixedServer.getDefaultThumbnail((int)(unmixedServer.nZSlices()/2), 0);			
    imageDisplay = ImageDisplay.create(unmixedImageData);
    imgThumbnail = imageDisplay.applyTransforms(imgThumbnail, null);
    imgThumbnail = ProjectImportImagesCommand.resizeForThumbnail(imgThumbnail);
    entry.setThumbnail(imgThumbnail);
    
    // Copy annotations from original image
    def originalHierarchy = imageData.getHierarchy()
    def unmixedHierarchy = unmixedImageData.getHierarchy()
    def originalAnnotations = originalHierarchy.getAnnotationObjects()
    
    originalAnnotations.each { annotation ->
        unmixedHierarchy.addObject(annotation)
    }
    
    // Save the modified entry
    entry.saveImageData(unmixedImageData)

    			    
    // Clean up
    unmixedServer.close()
    
    // Update the project
    QPEx.getQuPath().refreshProject()
    
    logger.info("Step 3 completed: Unmixed image '${newEntryName}' added to project")
}

// Usually a good idea to print something, so we know it finished
logger.info("=== Finished running ${this.class.simpleName} ===")


// Required imports
import javafx.application.Platform
import qupath.lib.scripting.QP
import qupath.lib.projects.Project
import qupath.lib.gui.scripting.QPEx
import qupath.lib.images.writers.ome.OMEPyramidWriter
import qupath.lib.roi.ROIs
import qupath.lib.roi.RectangleROI
import qupath.lib.objects.PathAnnotationObject
import qupath.lib.images.servers.CroppedImageServer
import qupath.lib.common.GeneralTools
import qupath.lib.regions.ImageRegion
import com.google.gson.GsonBuilder
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.nio.file.Paths
import java.nio.file.Files
import qupath.lib.gui.QuPathGUI
import qupath.lib.images.servers.TransformedServerBuilder
import qupath.lib.images.servers.ColorTransforms
import qupath.lib.gui.commands.ProjectCommands
import org.slf4j.LoggerFactory
import java.awt.Color
import qupath.lib.display.ChannelDisplayInfo
import qupath.lib.gui.tools.ColorToolsFX
import qupath.lib.gui.commands.ProjectImportImagesCommand
import qupath.lib.display.ImageDisplay

