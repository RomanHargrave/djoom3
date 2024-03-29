package neo.Renderer;

import neo.Renderer.Model.dynamicModel_t;
import static neo.Renderer.Model.dynamicModel_t.DM_CONTINUOUS;
import neo.Renderer.Model.idRenderModel;
import neo.Renderer.Model.modelSurface_s;
import neo.Renderer.Model_local.idRenderModelStatic;
import static neo.Renderer.RenderWorld.SHADERPARM_DIVERSITY;
import static neo.Renderer.RenderWorld.SHADERPARM_PARTICLE_STOPTIME;
import static neo.Renderer.RenderWorld.SHADERPARM_TIMEOFFSET;
import neo.Renderer.RenderWorld.renderEntity_s;
import neo.Renderer.tr_local.viewDef_s;
import static neo.Renderer.tr_trisurf.R_AllocStaticTriSurfIndexes;
import static neo.Renderer.tr_trisurf.R_AllocStaticTriSurfPlanes;
import static neo.Renderer.tr_trisurf.R_AllocStaticTriSurfVerts;
import static neo.Renderer.tr_trisurf.R_FreeStaticTriSurfVertexCaches;
import static neo.TempDump.sizeof;
import static neo.framework.DeclManager.declManager;
import static neo.framework.DeclManager.declType_t.DECL_PARTICLE;
import neo.framework.DeclParticle.idDeclParticle;
import neo.framework.DeclParticle.idParticleStage;
import neo.framework.DeclParticle.particleGen_t;
import neo.idlib.BV.Bounds.idBounds;
import neo.idlib.Text.Str.idStr;
import neo.idlib.geometry.DrawVert.idDrawVert;
import neo.idlib.math.Random.idRandom;

/**
 *
 */
public class Model_prt {

    static final String parametricParticle_SnapshotName = "_ParametricParticle_Snapshot_";

    /*
     ===============================================================================

     PRT model

     ===============================================================================
     */
    public static class idRenderModelPrt extends idRenderModelStatic {

        public idRenderModelPrt() {
            particleSystem = null;
        }

        @Override
        public void InitFromFile(String fileName) {
            name = new idStr(fileName);
            particleSystem = (idDeclParticle) declManager.FindType(DECL_PARTICLE, fileName);
        }

        @Override
        public void TouchData() {
            // Ensure our particle system is added to the list of referenced decls
            particleSystem = (idDeclParticle) declManager.FindType(DECL_PARTICLE, name);
        }

        @Override
        public dynamicModel_t IsDynamicModel() {
            return DM_CONTINUOUS;
        }

        @Override
        public idRenderModel InstantiateDynamicModel(renderEntity_s renderEntity, viewDef_s viewDef, idRenderModel cachedModel) {
            idRenderModelStatic staticModel;

            if (cachedModel != null && !RenderSystem_init.r_useCachedDynamicModels.GetBool()) {
//		delete cachedModel;
                cachedModel = null;
            }

            // this may be triggered by a model trace or other non-view related source, to which we should look like an empty model
            if (renderEntity == null || viewDef == null) {
//		delete cachedModel;
                return null;
            }

            if (RenderSystem_init.r_skipParticles.GetBool()) {
//		delete cachedModel;
                return null;
            }

            /*
             // if the entire system has faded out
             if ( renderEntity.shaderParms[SHADERPARM_PARTICLE_STOPTIME] && viewDef.renderView.time * 0.001f >= renderEntity.shaderParms[SHADERPARM_PARTICLE_STOPTIME] ) {
             delete cachedModel;
             return null;
             }
             */
            if (cachedModel != null) {

//		assert( dynamic_cast<idRenderModelStatic *>(cachedModel) != null );
//		assert( idStr::Icmp( cachedModel.Name(), parametricParticle_SnapshotName ) == 0 );
                staticModel = (idRenderModelStatic) cachedModel;

            } else {

                staticModel = new idRenderModelStatic();
                staticModel.InitEmpty(parametricParticle_SnapshotName);
            }

            particleGen_t g = new particleGen_t();

            g.renderEnt = renderEntity;
            g.renderView = viewDef.renderView;
            g.origin.Zero();
            g.axis.Identity();

            for (int stageNum = 0; stageNum < particleSystem.stages.Num(); stageNum++) {
                idParticleStage stage = particleSystem.stages.oGet(stageNum);

                if (null == stage.material) {
                    continue;
                }
                if (0 == stage.cycleMsec) {
                    continue;
                }
                if (stage.hidden) {		// just for gui particle editor use
                    staticModel.DeleteSurfaceWithId(stageNum);
                    continue;
                }

                idRandom steppingRandom = new idRandom(), steppingRandom2 = new idRandom();

                int stageAge = (int) (g.renderView.time + renderEntity.shaderParms[SHADERPARM_TIMEOFFSET] * 1000 - stage.timeOffset * 1000);
                int stageCycle = stageAge / stage.cycleMsec;
//                int inCycleTime = stageAge - stageCycle * stage.cycleMsec;

                // some particles will be in this cycle, some will be in the previous cycle
                steppingRandom.SetSeed(((stageCycle << 10) & idRandom.MAX_RAND) ^ (int) (renderEntity.shaderParms[SHADERPARM_DIVERSITY] * idRandom.MAX_RAND));
                steppingRandom2.SetSeed((((stageCycle - 1) << 10) & idRandom.MAX_RAND) ^ (int) (renderEntity.shaderParms[SHADERPARM_DIVERSITY] * idRandom.MAX_RAND));

                int count = stage.totalParticles * stage.NumQuadsPerParticle();

                int[] surfaceNum = new int[1];
                modelSurface_s surf;

                if (staticModel.FindSurfaceWithId(stageNum, surfaceNum)) {
                    surf = staticModel.surfaces.oGet(surfaceNum[0]);
                    R_FreeStaticTriSurfVertexCaches(surf.geometry);
                } else {
                    surf = staticModel.surfaces.Alloc();
                    surf.id = stageNum;
                    surf.shader = stage.material;
                    surf.geometry = new Model.srfTriangles_s();//R_AllocStaticTriSurf();
                    R_AllocStaticTriSurfVerts(surf.geometry, 4 * count);
                    R_AllocStaticTriSurfIndexes(surf.geometry, 6 * count);
                    R_AllocStaticTriSurfPlanes(surf.geometry, 6 * count);
                }

                int numVerts = 0;
                idDrawVert[] verts = surf.geometry.verts;

                for (int index = 0; index < stage.totalParticles; index++) {
                    g.index = index;

                    // bump the random
                    steppingRandom.RandomInt();
                    steppingRandom2.RandomInt();

                    // calculate local age for this index 
                    int bunchOffset = (int) (stage.particleLife * 1000 * stage.spawnBunching * index / stage.totalParticles);

                    int particleAge = stageAge - bunchOffset;
                    int particleCycle = particleAge / stage.cycleMsec;
                    if (particleCycle < 0) {
                        // before the particleSystem spawned
                        continue;
                    }
                    if (stage.cycles != 0 && particleCycle >= stage.cycles) {
                        // cycled systems will only run cycle times
                        continue;
                    }

                    if (particleCycle == stageCycle) {
                        g.random = steppingRandom;
                    } else {
                        g.random = steppingRandom2;
                    }

                    int inCycleTime = particleAge - particleCycle * stage.cycleMsec;

                    if (renderEntity.shaderParms[SHADERPARM_PARTICLE_STOPTIME] != 0
                            && g.renderView.time - inCycleTime >= renderEntity.shaderParms[SHADERPARM_PARTICLE_STOPTIME] * 1000) {
                        // don't fire any more particles
                        continue;
                    }

                    // supress particles before or after the age clamp
                    g.frac = (float) inCycleTime / (stage.particleLife * 1000);
                    if (g.frac < 0.0f) {
                        // yet to be spawned
                        continue;
                    }
                    if (g.frac > 1.0f) {
                        // this particle is in the deadTime band
                        continue;
                    }

                    // this is needed so aimed particles can calculate origins at different times
                    g.originalRandom = g.random;

                    g.age = g.frac * stage.particleLife;

                    // if the particle doesn't get drawn because it is faded out or beyond a kill region, don't increment the verts
                    numVerts += stage.CreateParticle(g, verts, numVerts);
                }

                // numVerts must be a multiple of 4
                assert ((numVerts & 3) == 0 && numVerts <= 4 * count);

                // build the indexes
                int numIndexes = 0;
                /*glIndex_t*/ int[] indexes = surf.geometry.indexes;
                for (int i = 0; i < numVerts; i += 4) {
                    indexes[numIndexes + 0] = i;
                    indexes[numIndexes + 1] = i + 2;
                    indexes[numIndexes + 2] = i + 3;
                    indexes[numIndexes + 3] = i;
                    indexes[numIndexes + 4] = i + 3;
                    indexes[numIndexes + 5] = i + 1;
                    numIndexes += 6;
                }

                surf.geometry.tangentsCalculated = false;
                surf.geometry.facePlanesCalculated = false;
                surf.geometry.numVerts = numVerts;
                surf.geometry.numIndexes = numIndexes;
                surf.geometry.bounds = new idBounds(stage.bounds);		// just always draw the particles
            }

            return staticModel;
        }

        @Override
        public idBounds Bounds(renderEntity_s ent) {
            return particleSystem.bounds;
        }

        @Override
        public float DepthHack() {
            return particleSystem.depthHack;
        }

        @Override
        public int Memory() {
            int total = 0;

            total += super.Memory();

            if (particleSystem != null) {
                total += sizeof(particleSystem);

                for (int i = 0; i < particleSystem.stages.Num(); i++) {
                    total += sizeof(particleSystem.stages.oGet(i));
                }
            }

            return total;
        }
//
        private idDeclParticle particleSystem;
    };
}
