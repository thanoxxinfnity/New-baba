package com.trellis.studio.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import io.github.sceneview.Scene
import io.github.sceneview.math.Position
import io.github.sceneview.node.ModelNode
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberNodes
import java.io.File

/**
 * Renders a GLB file with Filament (via SceneView) with orbit + pinch-zoom
 * touch gestures provided by the default camera manipulator.
 */
@Composable
fun ModelViewer(
    modelPath: String,
    modifier: Modifier = Modifier
) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val cameraNode = rememberCameraNode(engine) {
        position = Position(x = 0.0f, y = 0.5f, z = 2.5f)
    }
    val modelNode = remember(modelPath) {
        ModelNode(
            modelInstance = modelLoader.createModelInstance(File(modelPath)),
            scaleToUnits = 1.5f
        )
    }
    val nodes = rememberNodes { add(modelNode) }

    Scene(
        modifier = modifier,
        engine = engine,
        modelLoader = modelLoader,
        cameraNode = cameraNode,
        cameraManipulator = rememberCameraManipulator(
            orbitHomePosition = cameraNode.worldPosition,
            targetPosition = Position(0f, 0f, 0f)
        ),
        childNodes = nodes
    )
}
