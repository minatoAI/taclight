#version  330







//Material------------------------------
	
	
	
	
	

	
	
	

	
	
	
	
	
	
	

	
	
	
	
	
	
	
	
	

	
	

	

	





//Light Source--------------------------
	
	

	
	
	

	

//  //#define DIRECTIONAL_BLOCKLIGHT
	

	
	

//Held Light----------------------------
	
	

	
	

	

	
	
	
	

//  //#define FLASHLIGHT_HELDLIGHT
  	
	
	
	

	
	
	

//Shadow--------------------------------
	

	
	
	
	
	

	

	
	

	

//GI------------------------------------
	

	
	
	
	
	

	

//AO------------------------------------
	

	
	

	
	
	
	

	
//  //#define GTAO_FALLOFF_Z_OFFSET

	

	





//Texture-------------------------------
	
	

	
//  //#define ANISOTROPIC_FILTERING_NORMAL_SPECULAR

//POM-----------------------------------
//  //#define PARALLAX
	

	

	
	

	
	

	
 	

	

//PBR------------------------------
	
	

	

//  //#define TERRAIN_NORMAL_CLAMP
	
	

	
	
	

	

//  //#define LANDSCATTERING_REFLECTION
	
//  //#define LANDSCATTERING_REFRACTION
//  //#define VFOG_REFRACTION

	
	
	
	

//  //#define TEXTURE_PBR_POROSITY
	
	
	

	
	
	
	
//  //#define SSS_NORMAL

//Water & Rain--------------------------
	
	
	

	

//  //#define DISABLE_LOCAL_PRECIPITATION

	

	

	
	
	

	

//Sky Texture---------------------------
	

	
//  //#define SKYBOX_TEXTURE
//  //#define BILINEAR_MOON_TEXTURE
//  //#define BILINEAR_SKYBOX_TEXTURE

  	

//End Sky-------------------------------
//  //#define END_MANUAL_PLANET_CYCLE
	
	

//  //#define ACCRETIONDISC_DETAIL_ALONG_LONGTITUDE
	

//Texture Misc--------------------------
	
	

	
//  //#define TERRAIN_VS_TBN

	
	

 	
 
//  //#define GENERAL_GRASS_FIX
  
	
//  //#define SHADOW_WAVING_PLANTS
  	

//  //#define LINE_IMAGE_OUTPUT
	
	
	





//Sky & Volumetric----------------------
//  //#define INDOOR_FOG

	
	

//Planar Clouds-------------------------
	
	
	
	

//Volumtric Clouds----------------------
	
	

	
	
	
	
	
	
	

	
	
	
	
	
	
	

	
	
	
	
	
	


	

	
	

	
	
	
	

//Volumetric Fog------------------------
	
	
	

	
	

	
	
	

//  //#define VFOG_IGNORE_WORLDTIME
//  //#define VFOG_STAINED
//  //#define VFOG_CLOUD_SHADOW

	
	

	
	
	
	

	

	
	

//Land Scattering-----------------------
	
	
	
//  //#define LANDSCATTERING_SHADOW
	

//Sky Misc------------------------------
	
	

	

//  //#define ATMO_HORIZON
	





//TAA-----------------------------------
	
	
	
//  //#define TAA_BICUBIC_CURRENT
	
	
//  //#define TAA_CLIP_TO_CENTER
	
	
	

//DOF-----------------------------------
//  //#define DOF

	
	
	
	

	
	

//  //#define DOF_CATSEYE
	
	

	
	

//Exposure------------------------------
//  //#define MANUAL_EXPOSURE
	
	
	
	
	
	
	

	
	

//Motion Blur---------------------------
	
	
	
	

//Vignette------------------------------
	
	
	
//  //#define SNEAKING_VIGNETTE

//Bloom---------------------------------
	
	
	

	
//  //#define LENS_FLARE
	
	
//  //#define GLARE_FLARE_SHADOWBASED

//Color---------------------------------
	

	

	

//  //#define ADVANCED_COLOR
	
	
	
	

//FidelityFx----------------------------
	
	

//  //#define FSR
	
	

//Post Misc-----------------------------
	

//  //#define PURKINJE_EFFECT
	
	





//Distant Horizons----------------------
//  //#define DH_SHADOW
	
	
	

	
	
	

	

//TACZ----------------------------------
//  //#define DISABLE_HAND_TAA
//  //#define DISABLE_HAND_GI
//  //#define DISABLE_HAND_GI_VELOCITY
//  //#define DISABLE_HAND_SPECULAR

//YSM-----------------------------------
//  //#define DISABLE_PLAYER_TAA_MOTION_BLUR
//  //#define DISABLE_PLAYER_GI
//  //#define DISABLE_PLAYER_GI_VELOCITY
//  //#define DISABLE_PLAYER_SPECULAR
//  //#define DISABLE_PLAYER_SCREEN_SPACE_SHADOWS


//Misc----------------------------------
	
//  //#define CAVE_MODE

	
	





//DEBUG---------------------------------
//  //#define WHITE_DEBUG_WORLD
//  //#define DEBUG_COUNTER
//  //#define DISABLE_NIGHTVISION
//  //#define DISABLE_BLINDNESS_DARKNESS
//  //#define SODIUM_TEMPFIX

uniform int heldItemId;
uniform int heldBlockLightValue;
uniform int heldItemId2;
uniform int heldBlockLightValue2;
uniform int worldTime;
uniform int frameCounter;
uniform float frameTime;
uniform float frameTimeCounter;
uniform float sunAngle;
uniform float aspectRatio;
uniform float viewWidth;
uniform float viewHeight;
uniform float near;
uniform float far;
uniform vec3 cameraPosition;
uniform vec3 previousCameraPosition;
uniform mat4 gbufferModelView;
uniform mat4 gbufferModelViewInverse;
uniform mat4 gbufferPreviousModelView;
uniform mat4 gbufferProjection;
uniform mat4 gbufferProjectionInverse;
uniform mat4 gbufferPreviousProjection;
uniform mat4 shadowProjection;
uniform mat4 shadowModelView;
uniform mat4 shadowModelViewInverse;
uniform float wetness;
uniform ivec2 eyeBrightnessSmooth;
uniform int isEyeInWater;
uniform float nightVision;
uniform float blindness;
uniform int hideGUI;
uniform float darknessFactor;
uniform float darknessLightFactor;

uniform vec2 taaJitter;
uniform vec2 screenSize;
uniform vec2 pixelSize;
uniform float eyeBrightnessSmoothCurved;
uniform float eyeBrightnessZeroSmooth;
uniform float eyeSnowySmooth;
uniform float eyeNoPrecipitationSmooth;
uniform float eyeRxSmooth;
uniform float eyeRySmooth;
uniform float isSneakingSmooth;


	
	
	
	
	
	

	
	



uniform sampler2D colortex0;
uniform sampler2D colortex1;
uniform sampler2D colortex2;
uniform sampler2D colortex3;
uniform sampler2D colortex5;
uniform sampler2D colortex7;
uniform sampler2D depthtex0;
uniform sampler2D depthtex1;

uniform sampler2D noisetex;


	
	

	uniform sampler2D colortex4;
	uniform sampler2D colortex6;



	
		
	
		uniform sampler3D colortex8;
	
	uniform sampler2D depthtex2;

	
		
	
		
	





//Renewed modified by Tahnass

























float minVec3(vec3 v){
	return min(v.x, min(v.y, v.z));
}

float maxVec3(vec3 v){
	return max(v.x, max(v.y, v.z));
}

float remapSaturate(float x, float e0, float e1){
	return clamp((x - e0) /(e1 - e0), 0.0, 1.0);
}

float remap(float x, float minOrigin, float maxOrigin, float minNew, float maxNew){
	return (x - minOrigin) / (maxOrigin - minOrigin) * (maxNew - minNew) + minNew;
}

vec2 remap(vec2 x, vec2 minOrigin, vec2 maxOrigin, vec2 minNew, vec2 maxNew){
	return (x - minOrigin) / (maxOrigin - minOrigin) * (maxNew - minNew) + minNew;
}

vec3 remap(vec3 x, vec3 minOrigin, vec3 maxOrigin, vec3 minNew, vec3 maxNew){
	return (x - minOrigin) / (maxOrigin - minOrigin) * (maxNew - minNew) + minNew;
}

vec4 remap(vec4 x, vec4 minOrigin, vec4 maxOrigin, vec4 minNew, vec4 maxNew){
	return (x - minOrigin) / (maxOrigin - minOrigin) * (maxNew - minNew) + minNew;
}


float atan2(vec2 v){
	return v.x == 0.0 ?
		(1.0 - step(abs(v.y), 0.0)) * sign(v.y) * 1.57079632679 :
		atan(v.y / v.x) + step(v.x, 0.0) * sign(v.y) * 3.14159265359;
}

float facos(float x){
	float ax = abs(x);
	float res = -0.156583 * ax + 1.57079632679; 
	res *= intBitsToFloat(0x1fbd1df5 + (floatBitsToInt(1.0 - ax) >> 1));
	return x >= 0 ? res : 3.14159265359 - res;
}


vec3 LinearToGamma(vec3 c){
	return pow(c, vec3(1.0 / 2.2));
}

vec3 GammaToLinear(vec3 c){
	return pow(c, vec3(2.2));
}

float LinearToCurve(float c){
	return pow(c, 0.25);
}

float CurveToLinear(float c){
	c = c * c;
	return c * c;
}

vec3 LinearToCurve(vec3 c){
	return pow(c, vec3(0.25));
}

vec3 CurveToLinear(vec3 c){
	c = c * c;
	return c * c;
}


void DoNightEye(inout vec3 c){
	float luminance = dot(c, vec3(0.2125, 0.7154, 0.0721));
	c = mix(c, luminance * vec3(0.7771, 1.0038, 1.6190), vec3(0.5));
}


float Pack2x8(vec2 x){
	uvec2 u = uvec2(x * 255.0);
	return float((u.x << 8u) | u.y) / 65535.0;
}

vec2 Unpack2x8(float x){
	uint u = uint(x * 65535.0);
	return vec2(u >> 8u, u & 255u) / 255.0;
}

float Pack2x16(vec2 x){
	uvec2 u = uvec2(x * 65535.0);
	return uintBitsToFloat((u.x << 16u) | u.y);
}

vec2 Unpack2x16(float x){
	uint u = floatBitsToUint(x);
	return vec2(u >> 16u, u & 65535u) / 65535.0;
}


vec3 DecodeNormalTex(vec3 texNormal){
	vec3 normal = vec3(0.0, 0.0, 1.0);

	if (abs(texNormal.x + texNormal.y + texNormal.z - 1.5) < 1.488){
		normal.xy = texNormal.xy * 2.0 - 1.0;
		normal.xy = max(abs(normal.xy) - 1.0 / 255.0, 0.0) * sign(normal.xy);
		normal.z = sqrt(1.0 - dot(normal.xy, normal.xy));
	}
	return normal;
}

vec2 OctWrap(vec2 v) {
	return (1.0 - abs(v.yx)) * uintBitsToFloat((floatBitsToUint(v.xy) & 0x80000000u) | 0x3f800000u);
}

vec2 EncodeNormal(vec3 n){
	n.xy /= (abs(n.x) + abs(n.y) + abs(n.z));
	n.xy = n.z >= 0.0 ? n.xy : OctWrap(n.xy);

	return n.xy * 0.5 + 0.5;
}

vec3 DecodeNormal(vec2 en){
	vec2 n = en * 2.0 - 1.0;

	float nz = 1.0 - abs(n.x) - abs(n.y);
	return normalize(vec3(nz >= 0 ? n : OctWrap(n), nz));
}


float D_Walter(float NdotH, float roughness){
	float roughness2 = roughness * roughness;
	float k = NdotH * NdotH * (roughness2 - 1.0) + 1.0;
	return roughness2 / (3.14159265359 * k * k);
}

float F_Schlick(float VdotH, float f0, float f90){
	VdotH = 1.0 - VdotH;
	float VdotH2 = VdotH * VdotH;
	return f0 + (f90 - f0) * VdotH2 * VdotH2 * VdotH;
}

float V_Schlick(float NdotL, float NdotV, float roughness){
	float k = roughness * 0.5;
	return NdotL / ((NdotL * (1.0 - k) + k) * (NdotV * (1.0 - k) + k));
}

//Brent Burley. 2012. Physically Based Shading at Disney. Physically Based Shading in Film and Game Production, ACM SIGGRAPH 2012 Courses.
float Fd_Burley(vec3 n, vec3 v, vec3 l, float roughness){
	vec3 h = normalize(v + l);
	float LdotH = clamp(dot(l, h), 0.0, 1.0);
	float NdotL = clamp(dot(n, l), 0.0, 1.0);
	float NdotV = clamp(dot(n, v), 0.0, 1.0);

	float f90 = 0.5 + 2.0 * roughness * LdotH * LdotH;

	float lightScatter = F_Schlick(NdotL, 1.0, f90);
	float viewScatter = F_Schlick(NdotV, 1.0, f90);

	return NdotL * lightScatter * viewScatter * 0.31830988618;
}

float SpecularGGX(vec3 n, vec3 v, vec3 l, float roughness, float f0){
	vec3 h = normalize(v + l);
	float NdotH = clamp(dot(n, h), 0.0, 1.0);
	float LdotH = clamp(dot(l, h), 0.0, 1.0);
	float NdotL = clamp(dot(n, l), 0.0, 1.0);
	float NdotV = clamp(dot(n, v), 0.0, 1.0);

	float GGX = F_Schlick(LdotH, f0, 1.0);
	GGX *= V_Schlick(NdotL, NdotV, roughness);
	GGX *= D_Walter(NdotH, roughness);

	return GGX;
}


vec3 Blackbody(float temperature){
	// https://en.wikipedia.org/wiki/Planckian_locus
	const mat2x4 splineX = mat2x4(-0.2661293e9, -0.2343589e6, 0.8776956e3, 0.179910,
								  -3.0258469e9,  2.1070479e6, 0.2226347e3, 0.240390);

	const mat3x4 splineY = mat3x4(-1.1063814, -1.34811020, 2.18555832, -0.20219683,
								  -0.9549476, -1.37418593, 2.09137015, -0.16748867,
								   3.0817580, -5.87338670, 3.75112997, -0.37001483);

	float rt = 1.0 / temperature;
	float rt2 = rt * rt;
	vec4 coeffX = vec4(rt2 * rt, rt2, rt, 1.0);

	float x = dot(coeffX, temperature < 4000.0 ? splineX[0] : splineX[1]);
	float x2 = x * x;
	vec4 coeffY = vec4(x2 * x, x2, x, 1.0);

	float z = 1.0 / dot(coeffY, temperature < 2222.0 ? splineY[0] : temperature < 4000.0 ? splineY[1] : splineY[2]);

	vec3 xyz = vec3(x * z, 1.0, z);
	xyz.z -= xyz.x + 1.0;

	const mat3 xyzToSrgb = mat3( 3.24097, -0.96924,  0.05563,
								-1.53738,  1.87597, -0.20398,
								-0.49861,  0.04156,  1.05697);

	return max(xyzToSrgb * xyz, vec3(0.0));
}


vec3 RayPlaneIntersection(vec3 ori, vec3 dir, vec3 normal){
	float rayPlaneAngle = dot(dir, normal);

	float planeRayDist = 1e8;
	vec3 intersectionPos = dir * planeRayDist;

	if (rayPlaneAngle > 0.0001 || rayPlaneAngle < -0.0001){
		planeRayDist = dot(-ori, normal) / rayPlaneAngle;
		intersectionPos = ori + dir * planeRayDist;
	}

	return intersectionPos;
}

vec2 RaySphereIntersection(vec3 ori, vec3 dir, float radius){
	float b = dot(ori, dir);
	float c = -radius * radius + dot(ori, ori);
	float d = b * b - c;

	vec2 intersection = vec2(1e10, -1e10);

	if (d >= 0.0){
		d = sqrt(d);
		intersection = vec2(-b - d, -b + d);
	}

	return intersection;
}


float RayleighPhaseFunction(float nu) {
	return 0.059683104 * (nu * nu + 1.0);
}

float MiePhaseFunction(float g, float nu) {
	float gg = g * g;
	float k = 0.1193662 * (1.0 - gg) / (2.0 + gg);
	return k * (1.0 + nu * nu) * pow(1.0 + gg - 2.0 * g * nu, -1.5);
}


float InterleavedGradientNoise(vec2 c){
	return fract(52.9829189 * fract(0.06711056 * c.x + 0.00583715 * c.y));
}

float bayer2(vec2 a) {
	a = floor(a);

	return fract(dot(a, vec2(0.5, a.y * 0.75)));
}

float bayer4  (vec2 a) { return bayer2 (0.5   * a) * 0.25     + bayer2(a); }
float bayer8  (vec2 a) { return bayer4 (0.5   * a) * 0.25     + bayer2(a); }
float bayer16 (vec2 a) { return bayer4 (0.25  * a) * 0.0625   + bayer4(a); }
float bayer32 (vec2 a) { return bayer8 (0.25  * a) * 0.0625   + bayer4(a); }
float bayer64 (vec2 a) { return bayer8 (0.125 * a) * 0.015625 + bayer8(a); }
float bayer128(vec2 a) { return bayer16(0.125 * a) * 0.015625 + bayer8(a); }

//https://extremelearning.com.au/unreasonable-effectiveness-of-quasirandom-sequences/
float Sequences_R1(float n) {
	const float alpha = 1.0 / 1.61803398875;
	return fract(0.5 + n * alpha);
}

vec2 Sequences_R2(float n) {
	const vec2 alpha = 1.0 / vec2(1.32471795724, 1.32471795724 * 1.32471795724);
	return fract(0.5 + n * alpha);
}


/*
const int 	colortex0Format         = RGBA8;
const vec4 	colortex0ClearColor 	= vec4(0.0, 0.0, 0.0, 1.0);
const int 	colortex1Format         = RGBA16;
const int 	colortex2Format         = RGBA16;
const int 	colortex3Format 		= RGBA16;
const int 	colortex4Format 		= RGBA16;
const int 	colortex5Format 		= RGBA16;
const int 	colortex6Format 		= RGBA16;
const int 	colortex7Format 		= RGBA16;
const int 	colortex8Format 		= RGBA8;

const bool	colortex0Clear          = true;
const bool	colortex1Clear          = false;
const bool	colortex2Clear          = false;
const bool	colortex3Clear          = true;
const bool	colortex4Clear          = true;
const bool	colortex5Clear          = true;
const bool	colortex6Clear          = true;
const bool	colortex7Clear          = false;

const float shadowIntervalSize 			= 4.0;
const float shadowDistanceRenderMul 	= 1.0;

const bool 	shadowHardwareFiltering1 	= true;
const bool 	shadowtex0Mipmap 			= true;
const bool 	shadowtex0Nearest 			= false;
const bool 	shadowtex1Mipmap 			= false;
const bool 	shadowtex1Nearest 			= false;
const bool 	shadowcolor0Mipmap 			= false;
const bool 	shadowcolor0Nearest 		= false;
const bool 	shadowcolor1Mipmap 			= false;
const bool 	shadowcolor1Nearest 		= false;


const int 	noiseTextureResolution 	= 64;

const float wetnessHalflife 		= 200.0; 	//[10.0 20.0 30.0 50.0 75.0 100.0 150.0 200.0 300.0 500.0]
const float drynessHalflife 		= 50.0; 	//[10.0 20.0 30.0 50.0 75.0 100.0 150.0 200.0 300.0 500.0]
const float eyeBrightnessHalflife 	= 10.0;

const float sunPathRotation 		= -30.0; 	// [-90.0 -89.0 -88.0 -87.0 -86.0 -85.0 -84.0 -83.0 -82.0 -81.0 -80.0 -79.0 -78.0 -77.0 -76.0 -75.0 -74.0 -73.0 -72.0 -71.0 -70.0 -69.0 -68.0 -67.0 -66.0 -65.0 -64.0 -63.0 -62.0 -61.0 -60.0 -59.0 -58.0 -57.0 -56.0 -55.0 -54.0 -53.0 -52.0 -51.0 -50.0 -49.0 -48.0 -47.0 -46.0 -45.0 -44.0 -43.0 -42.0 -41.0 -40.0 -39.0 -38.0 -37.0 -36.0 -35.0 -34.0 -33.0 -32.0 -31.0 -30.0 -29.0 -28.0 -27.0 -26.0 -25.0 -24.0 -23.0 -22.0 -21.0 -20.0 -19.0 -18.0 -17.0 -16.0 -15.0 -14.0 -13.0 -12.0 -11.0 -10.0 -9.0 -8.0 -7.0 -6.0 -5.0 -4.0 -3.0 -2.0 -1.0 0.0 1.0 2.0 3.0 4.0 5.0 6.0 7.0 8.0 9.0 10.0 11.0 12.0 13.0 14.0 15.0 16.0 17.0 18.0 19.0 20.0 21.0 22.0 23.0 24.0 25.0 26.0 27.0 28.0 29.0 30.0 31.0 32.0 33.0 34.0 35.0 36.0 37.0 38.0 39.0 40.0 41.0 42.0 43.0 44.0 45.0 46.0 47.0 48.0 49.0 50.0 51.0 52.0 53.0 54.0 55.0 56.0 57.0 58.0 59.0 60.0 61.0 62.0 63.0 64.0 65.0 66.0 67.0 68.0 69.0 70.0 71.0 72.0 73.0 74.0 75.0 76.0 77.0 78.0 79.0 80.0 81.0 82.0 83.0 84.0 85.0 86.0 87.0 88.0 89.0 90.0]

const float ambientOcclusionLevel 	= 1.0;
const int 	superSamplingLevel 		= 0;
*/


const int 	shadowMapResolution 		= 2048; 	// [1024 2048 4096 8192 16384 32768]
const float shadowDistance 				= 192.0; 	// [64.0 96.0 128.0 192.0 256.0 384.0 512.0 768.0 1024.0 1536.0 2048.0]


uniform sampler2D shadowtex0;
uniform sampler2DShadow shadowtex1;
uniform sampler2D shadowcolor0;
uniform sampler2D shadowcolor1;


/* DRAWBUFFERS:1 */
layout(location = 0) out vec4 compositeOutput1;


ivec2 texelCoord = ivec2(gl_FragCoord.xy);
vec2 texCoord = gl_FragCoord.xy * pixelSize;

in vec3 worldShadowVector;
in vec3 shadowVector;
in vec3 worldSunVector;
in vec3 worldMoonVector;

in vec3 colorShadowlight;
in vec3 colorSunlight;
in vec3 colorMoonlight;

in vec3 colorSkylight;
in vec3 colorSunSkylight;
in vec3 colorMoonSkylight;

in vec3 colorTorchlight;

in float timeNoon;
in float timeMidnight;




float CurveBlockLightSky(float blockLight){
	blockLight = 1.0 - pow(1.0 - blockLight * 0.9, 0.7);

	blockLight = clamp(blockLight * blockLight * blockLight * 1.95, 0.0, 1.0);

	return blockLight;
}

float CurveBlockLightTorch(float blockLight){
	float dist = (1.0 - blockLight) * 15.0 + 1.0;
	dist = dist * dist;

	float boost = clamp(blockLight * 2.0 - 1.0, 0.0, 1.0);
	blockLight = blockLight + boost * boost;
	blockLight /= dist;

	return blockLight;
}

struct Material{
	float roughness;
	float metalness;
	float f0;
	float emissiveness;
	float scattering;
	float reflectionStrength;
};

struct GbufferData{
	vec3 albedo;
	vec4 albedoW;
	vec3 normalL;
	vec3 normalW;
	float depthL;
	float depthW;
	vec2 lightmapL;
	vec2 lightmapW;
	float materialIDL;
	float materialIDW;
	float waterMask;
	float rainAlpha;
	float parallaxShadow;
	Material material;
};

struct Ray{
	vec3 dir;
	vec3 origin;
};

struct Plane{
	vec3 normal;
	vec3 origin;
};

struct Intersection{
	vec3 pos;
	float distance;
	float angle;
};


const Material airMaterial 		= Material(1.0, 0.0, 0.0,   0.0, 0.0, 0.0);
const Material material_water 	= Material(0.0, 0.0, 0.02,  0.0, 0.0, 1.0);
const Material material_glass 	= Material(0.0, 0.0, 0.042, 0.0, 0.0, 1.0);
const Material material_ice 	= Material(0.0, 0.0, 0.018, 0.0, 0.0, 1.0);

Material MaterialFromTex(inout vec3 baseTex, vec4 specTex, float wet){
	
		
			
		
			
		
	
		float porosity = 0.3;
	

	baseTex *= 1.0 - wet * intBitsToFloat(0x1fbd1df5 + (floatBitsToInt(porosity) >> 1)) * 0.7;


	Material material;


	float rawSmoothness = mix(specTex.r, 1.0, clamp(wet *(1.7 - porosity * 0.7), 0.0, 1.0));
	material.roughness = 1.0 - rawSmoothness;
	material.roughness *= material.roughness;


	float rawMetalness = specTex.g;
	
		rawMetalness = clamp(rawMetalness + step(0.9, rawMetalness), 0.0, 1.0);
	
	
		material.metalness = clamp(rawMetalness * 1.1 - 0.1, 0.0, 1.0);
	
		
	
	material.f0 = rawMetalness * 0.96 + 0.04;


	
		material.reflectionStrength = clamp(2.0 - material.roughness * 5.0, 0.0, 1.0);
	
		
	
	material.reflectionStrength = clamp(material.reflectionStrength + material.metalness * 1e10 + wet * 3.0, 0.0, 1.0);


	material.emissiveness = specTex.a;
	
		material.emissiveness -= step(1.0, material.emissiveness);
	
	material.emissiveness = pow(material.emissiveness, 2.2);


	
		material.scattering = clamp((specTex.b * 255.0 - 64.0) / 191.0, 0.0, 1.0) * 1.0 + 0.0;
	
		
	


	return material;
}



GbufferData GetGbufferData(){
	GbufferData data;

	vec4 gbuffer0 = texelFetch(colortex0, texelCoord, 0);
	vec4 gbuffer3 = texelFetch(colortex3, texelCoord, 0);
	vec4 gbuffer4 = texelFetch(colortex4, texelCoord, 0);
	vec4 gbuffer5 = texelFetch(colortex5, texelCoord, 0);
	vec4 gbuffer6 = texelFetch(colortex6, texelCoord, 0);

	data.albedo 		= GammaToLinear(gbuffer0.rgb);
	data.albedoW 		= vec4(Unpack2x8(gbuffer6.r), Unpack2x8(gbuffer6.g));
	data.albedoW.rgb 	= GammaToLinear(data.albedoW.rgb);
	data.normalL 		= DecodeNormal(gbuffer3.rg);
	data.normalW 		= DecodeNormal(gbuffer4.rg);
	data.depthL 		= texelFetch(depthtex1, texelCoord, 0).x;
	data.depthW 		= texelFetch(depthtex0, texelCoord, 0).x;
	data.lightmapL 		= gbuffer3.ba;
	data.lightmapW 		= gbuffer4.ba;
	data.lightmapL 		= vec2(data.lightmapL.r, CurveBlockLightSky(data.lightmapL.g));
	data.lightmapW 		= vec2(data.lightmapW.r, CurveBlockLightSky(data.lightmapW.g));
	data.materialIDL 	= gbuffer5.b;
	data.materialIDW 	= gbuffer6.b;
	data.waterMask 		= gbuffer6.a;
	data.rainAlpha 		= 1.0 - gbuffer0.a;
	vec2 gbuffer5a 		= Unpack2x8(gbuffer5.a);
	data.parallaxShadow = gbuffer5a.y;

	
		vec4 specTex = vec4(Unpack2x8(gbuffer5.r), Unpack2x8(gbuffer5.g));
		data.material = MaterialFromTex(data.albedo, specTex, gbuffer5a.x);

		
			
		
	
		
	

	return data;
}


struct MaterialMask{
	float sky;
	float land;
	float grass;
	float leaves;
	float hand;
	float entityPlayer;
	float water;
	float stainedGlass;
	float ice;

	float entitiesLitHigh;
	float entitiesLitMedium;
	float entitiesLitLow;
	float lightning;
	float entitiesSnow;

	float torch;
	float lava;
	float glowstone;
	float fire;
	float redstoneTorch;
	float redstone;
	float soulFire;
	float amethyst;
	float oxidizedBulb;

	float particle;
	float particlelit;

	float endPortal;

	float selection;
};

MaterialMask CalculateMasks(float materialIDs){
	MaterialMask mask;

	materialIDs = floor(materialIDs * 255.0);

	mask.sky				= float(materialIDs == 0.0);
	mask.land				= float(materialIDs == 1.0);
	mask.grass				= float(materialIDs == 2.0 || materialIDs == 16.0);
	mask.leaves				= float(materialIDs == 3.0);
	mask.hand				= float(materialIDs == 4.0);

	mask.water				= float(materialIDs == 6.0);
	mask.stainedGlass		= float(materialIDs == 7.0);
	mask.ice				= float(materialIDs == 8.0);

	mask.entityPlayer		= float(materialIDs == 10.0);
	mask.entitiesLitHigh	= float(materialIDs == 11.0 || materialIDs == 16.0);
	mask.entitiesLitMedium	= float(materialIDs == 12.0);
	mask.entitiesLitLow		= float(materialIDs == 13.0);
	mask.entitiesSnow		= float(materialIDs == 14.0 || materialIDs == 16.0);
	mask.lightning			= float(materialIDs == 15.0);

	mask.torch				= float(materialIDs == 21.0);
	mask.lava				= float(materialIDs == 22.0);
	mask.glowstone			= float(materialIDs == 20.0);
	mask.fire				= float(materialIDs == 23.0);
	mask.redstoneTorch		= float(materialIDs == 24.0);
	mask.redstone			= float(materialIDs == 25.0);
	mask.soulFire			= float(materialIDs == 26.0);
	mask.amethyst			= float(materialIDs == 27.0);
	mask.oxidizedBulb		= float(materialIDs == 28.0);

	mask.particle			= float(materialIDs == 40.0);
	mask.particlelit		= float(materialIDs == 41.0);

	mask.endPortal			= float(materialIDs == 50.0);

	mask.selection			= float(materialIDs == 200.0);

	return mask;
}

void FixParticleMask(inout MaterialMask materialMaskSoild, inout MaterialMask materialMask, inout float depthL, in float depthW){
	
	if(materialMaskSoild.particle > 0.5 || materialMaskSoild.particlelit > 0.5){
		materialMask.particle = 1.0;
		materialMask.water = 0.0;
		materialMask.stainedGlass = 0.0;
		materialMask.ice = 0.0;
		materialMask.sky = 0.0;
		depthL = depthW;
	}
	
}

void FixParticleMask(inout MaterialMask materialMaskSoild, inout MaterialMask materialMask){
	
	if(materialMaskSoild.particle > 0.5 || materialMaskSoild.particlelit > 0.5){
		materialMask.particle = 1.0;
		materialMask.water = 0.0;
		materialMask.stainedGlass = 0.0;
		materialMask.ice = 0.0;
		materialMask.sky = 0.0;
	}
	
}

void ApplyMaterial(inout Material material, in MaterialMask materialMask, inout bool isSmooth){
	if (materialMask.water > 0.5){
		material = material_water;
		isSmooth = true;
	}
	if (materialMask.stainedGlass > 0.5){
		material = material_glass;
		isSmooth = true;
	}
	if (materialMask.ice > 0.5){
		material = material_ice;
		isSmooth = true;
	}
}
vec3 ViewPos_From_ScreenPos(vec2 coord, float depth){
	
		coord -= taaJitter * 0.5;
	
	vec3 ndcPos = vec3(coord, depth) * 2.0 - 1.0;
	vec3 viewPos = vec3(vec2(gbufferProjectionInverse[0][0], gbufferProjectionInverse[1][1]) * ndcPos.xy, 0.0) + gbufferProjectionInverse[3].xyz;
	return viewPos / (gbufferProjectionInverse[2][3] * ndcPos.z + gbufferProjectionInverse[3][3]);
}

vec3 ViewPos_From_ScreenPos_Raw(vec2 coord, float depth){
	vec3 ndcPos = vec3(coord, depth) * 2.0 - 1.0;
	vec3 viewPos = vec3(vec2(gbufferProjectionInverse[0][0], gbufferProjectionInverse[1][1]) * ndcPos.xy, 0.0) + gbufferProjectionInverse[3].xyz;
	return viewPos / (gbufferProjectionInverse[2][3] * ndcPos.z + gbufferProjectionInverse[3][3]);
}

vec3 ScreenPos_From_ViewPos(vec3 viewPos){
	vec3 screenPos = vec3(gbufferProjection[0][0], gbufferProjection[1][1], gbufferProjection[2][2]) * viewPos + gbufferProjection[3].xyz;
	screenPos = screenPos * (0.5 / -viewPos.z) + 0.5;
	
		screenPos.xy += taaJitter * 0.5;
	
	return screenPos;
}

vec3 ScreenPos_From_ViewPos_Raw(vec3 viewPos){
	vec3 screenPos = vec3(gbufferProjection[0][0], gbufferProjection[1][1], gbufferProjection[2][2]) * viewPos + gbufferProjection[3].xyz;
	return screenPos * (0.5 / -viewPos.z) + 0.5;
}

float LinearDepth_From_ScreenDepth(float depth){
	depth = depth * 2.0 - 1.0;
	return 1.0 / (depth * gbufferProjectionInverse[2][3] + gbufferProjectionInverse[3][3]);
}

float ScreenDepth_From_LinearDepth(float depth){
	depth = (1.0 / depth - gbufferProjectionInverse[3][3]) / gbufferProjectionInverse[2][3];
	return depth * 0.5 + 0.5;
}



	
		
			
		
		
		
		
	

	
		
		
		
	

	
		
		
		
			
		
		
	

	
		
		
	

	
		
		
	

	
		
		
	

	
		
		
		
    	
	




vec3 ShadowScreenPos_From_WorldPos_Distorted(vec3 worldPos){
	vec3 shadowPos = mat3(shadowModelView) * worldPos + shadowModelView[3].xyz;
	shadowPos *= vec3(shadowProjection[0][0], shadowProjection[0][0], -shadowProjection[0][0] * 0.5);

	float dist = length(shadowPos.xy);
	float distortFactor = (1.0 - 0.9) + dist * 0.9;
	shadowPos.xy *= 0.95 / distortFactor;

	return shadowPos * 0.5 + 0.5;
}

vec3 ShadowScreenPos_From_WorldPos(vec3 worldPos){
	vec3 shadowPos = mat3(shadowModelView) * worldPos + shadowModelView[3].xyz;
	shadowPos *= vec3(shadowProjection[0][0], shadowProjection[0][0], -shadowProjection[0][0] * 0.5);

	return shadowPos * 0.5 + 0.5;
}

vec3 ShadowScreenPos_From_WorldPos_WithoutZScaling(vec3 worldPos){
	vec3 shadowPos = mat3(shadowModelView) * worldPos + shadowModelView[3].xyz;
	shadowPos *= vec3(shadowProjection[0][0], shadowProjection[0][0], -shadowProjection[0][0] * 0.5);
	
	return shadowPos * 0.5 + 0.5;
}

vec2 DistortShadowScreenPos(vec2 shadowPos){
	shadowPos = shadowPos * 2.0 - 1.0;

	float dist = length(shadowPos.xy);
	float distortFactor = (1.0 - 0.9) + dist * 0.9;
	shadowPos *= 0.95 / distortFactor;

	return shadowPos * 0.5 + 0.5;
}



vec2 BlueNoise(){
	return texelFetch(noisetex, ivec2(gl_FragCoord.xy) % 64, 0).xy;
}

vec2 BlueNoiseTemproal(){
	return fract(texelFetch(noisetex, ivec2(gl_FragCoord.xy) % 64, 0).xy + vec2(1.61803398875, 1.32471795724) * vec2(frameCounter % 64));
}

float BayerTemproal(){
	return fract(bayer64(gl_FragCoord.xy) + 1.61803398875 * float(frameCounter % 128));
}



































//////////Utility functions///////////////////////
//////////Utility functions///////////////////////


float ClampRadius(float r){
	return clamp(r, 6360.0, 6420.0);
}

float SafeSqrt(float a){
	return sqrt(max(a, 0.0));
}

vec3 RenderSunDisc(vec3 worldDir, vec3 sunDir){
	float d = dot(worldDir, sunDir);

	float size = 5e-5;
	float hardness = 4e4;

	float disc = clamp((d -(1.0 - size)) * hardness, 0.0, 1.0) * clamp((d -(1.0 - size)) * hardness, 0.0, 1.0) * (3.0 - 2.0 * clamp((d -(1.0 - size)) * hardness, 0.0, 1.0));
	disc *= disc;

	return vec3(1.1114, 0.9756, 0.9133) * disc;
}

float RenderMoonDisc(vec3 worldDir, vec3 moonDir){
	float d = dot(worldDir, moonDir);

	float size = 5e-5;
	float hardness = 1e5;

	float disc = clamp((d -(1.0 - size)) * hardness, 0.0, 1.0) * clamp((d -(1.0 - size)) * hardness, 0.0, 1.0) * (3.0 - 2.0 * clamp((d -(1.0 - size)) * hardness, 0.0, 1.0));
	return disc * disc;
}

float RenderMoonDiscReflection(vec3 worldDir, vec3 moonDir){
	float d = dot(worldDir, moonDir);

	float size = 0.0025;
	float hardness = 300.0;

	float disc = clamp((d -(1.0 - size)) * hardness, 0.0, 1.0) * clamp((d -(1.0 - size)) * hardness, 0.0, 1.0) * (3.0 - 2.0 * clamp((d -(1.0 - size)) * hardness, 0.0, 1.0));
	return disc * disc;
}



//////////Intersections///////////////////////////
//////////Intersections///////////////////////////

float DistanceToTopAtmosphereBoundary(
	float r,
	float mu
	){
		float discriminant = r * r * (mu * mu - 1.0) + 6420.0 * 6420.0;
		return max(-r * mu + SafeSqrt(discriminant), 0.0);
}

float DistanceToBottomAtmosphereBoundary(
	float r,
	float mu
	){
		float discriminant = r * r * (mu * mu - 1.0) + 6360.0 * 6360.0;
		return max(-r * mu - SafeSqrt(discriminant), 0.0);
}

bool RayIntersectsGround(
	float r,
	float mu
	){
		return mu < 0.0 && r * r * (mu * mu - 1.0) + 6360.0 * 6360.0>= 0.0;
}



//////////Density at altitude/////////////////////
//////////Density at altitude/////////////////////


float GetProfileDensityRayleighMie(
	float exp_scale,
	float altitude
	){
		return exp2(exp_scale * altitude);
}

float GetProfileDensityAbsorption(
	float width,
	vec4 profile,
	float altitude
	){
		return altitude < width ?
			profile.x * altitude + profile.y :
			profile.z * altitude + profile.w;
}


//////////Coord Transforms////////////////////////
//////////Coord Transforms////////////////////////

float GetTextureCoordFromUnitRange(float x, float texture_size) {
	return 0.5 / texture_size + x * (1.0 - 1.0 / texture_size);
}

float GetCombinedTextureCoordFromUnitRange(float x, float original_texture_size, float combined_texture_size) {
	return 0.5 / combined_texture_size + x * (original_texture_size / combined_texture_size - 1.0 / combined_texture_size);
}


vec4 GetScatteringTextureUvwzFromRMuMuSNu(
	float r,
	float mu,
	float mu_s,
	float nu,
	bool ray_r_mu_intersects_ground
	){
		float H = sqrt(6420.0 * 6420.0 - 6360.0* 6360.0);

		float rho = SafeSqrt(r * r - 6360.0* 6360.0);
		float u_r = GetCombinedTextureCoordFromUnitRange(rho / H, 32.0, 33.0);

		float r_mu = r * mu;
		float discriminant = r_mu * r_mu - r * r + 6360.0* 6360.0;
		float u_mu;

		if (ray_r_mu_intersects_ground){
			float d = -r_mu - SafeSqrt(discriminant);
			float d_min = r - 6360.0;
			float d_max = rho;
			u_mu = 0.5 - 0.5 * GetTextureCoordFromUnitRange(float(d_max != d_min) * (d - d_min) / (d_max - d_min), 128.0 / 2.0);
		}else{
			float d = -r_mu + SafeSqrt(discriminant + H * H);
			float d_min = 6420.0 - r;
			float d_max = rho + H;
			u_mu = 0.5 + 0.5 * GetTextureCoordFromUnitRange((d - d_min) / (d_max - d_min), 128.0 / 2.0);
		}

		float d = DistanceToTopAtmosphereBoundary(6360.0, mu_s);
		float d_min = 6420.0 - 6360.0;
		float d_max = H;
		float a = (d - d_min) / (d_max - d_min);
		float D = DistanceToTopAtmosphereBoundary(6360.0, -0.5);
		float A = (D - d_min) / (d_max - d_min);
		float u_mu_s = GetTextureCoordFromUnitRange(max(1.0 - a / A, 0.0) / (1.0 + a), 32.0);
		float u_nu = (nu + 1.0) / 2.0;
		return vec4(u_nu, u_mu_s, u_mu, u_r);
}



//////////Transmittance Lookup////////////////////
//////////Transmittance Lookup////////////////////

vec2 GetTransmittanceTextureUvFromRMu(
	float r,
	float mu
	){
		float H = sqrt(6420.0 * 6420.0 - 6360.0* 6360.0);

		float rho = SafeSqrt(r * r - 6360.0* 6360.0);

		float d = DistanceToTopAtmosphereBoundary(r, mu);
		float d_min = 6420.0 - r;
		float d_max = rho + H;
		float x_mu = (d - d_min) / (d_max - d_min);
		float x_r = rho / H;
		return vec2(GetCombinedTextureCoordFromUnitRange(x_mu, 256.0, 512.0),
					GetCombinedTextureCoordFromUnitRange(x_r, 64.0, 128.0));
}

vec3 GetTransmittanceToTopAtmosphereBoundary(
	float r,
	float mu
	){
		vec2 uv = GetTransmittanceTextureUvFromRMu(r, mu);
		
			return vec3(textureLod(colortex8, vec3(uv, 32.5 / 33.0), 0.0));
		
			
		
}

vec3 GetTransmittance(
	float r,
	float mu,
	float d,
	bool ray_r_mu_intersects_ground
	){
		float r_d = ClampRadius(sqrt(d * d + 2.0 * r * mu * d + r * r));
		float mu_d = clamp((r * mu + d) / r_d, -1.0, 1.0);

		if (ray_r_mu_intersects_ground) {
			return min(
				GetTransmittanceToTopAtmosphereBoundary(r_d, -mu_d) /
				GetTransmittanceToTopAtmosphereBoundary(r, -mu),
			vec3(1.0));
		} else {
			return min(
				GetTransmittanceToTopAtmosphereBoundary(r, mu) /
				GetTransmittanceToTopAtmosphereBoundary(r_d, mu_d),
			vec3(1.0));
		}
}

vec3 GetTransmittanceToSun(
	float r,
	float mu_s
	){
		float sin_theta_h = 6360.0/ r;
		float cos_theta_h = -sqrt(max(1.0 - sin_theta_h * sin_theta_h, 0.0));

		return GetTransmittanceToTopAtmosphereBoundary(r, mu_s) *
			smoothstep(-sin_theta_h * 0.005,
					   sin_theta_h * 0.005,
					   mu_s - cos_theta_h);
}



//////////Scattering Lookup///////////////////////
//////////Scattering Lookup///////////////////////

vec3 GetExtrapolatedSingleMieScattering(
	vec4 scattering
	){
		if (scattering.r <= 0.0){
			return vec3(0.0);
		}
		return scattering.rgb * scattering.a / scattering.r *
			(vec3(0.008396, 0.014590, 0.033100).r / vec3(0.003996).r) *
			(vec3(0.003996) / vec3(0.008396, 0.014590, 0.033100));
}

vec3 GetCombinedScattering(
	float r,
	float mu,
	float mu_s,
	float nu,
	bool ray_r_mu_intersects_ground,
	out vec3 single_mie_scattering
	){
		vec4 uvwz = GetScatteringTextureUvwzFromRMuMuSNu(r, mu, mu_s, nu, ray_r_mu_intersects_ground);
		float tex_coord_x = uvwz.x * (16.0 - 1.0);
		float tex_x = floor(tex_coord_x);
		float lerp = tex_coord_x - tex_x;
		vec3 uvw0 = vec3((tex_x + uvwz.y) / 16.0, uvwz.z, uvwz.w);
		vec3 uvw1 = vec3((tex_x + 1.0 + uvwz.y) / 16.0, uvwz.z, uvwz.w);

		
			vec4 combined_scattering = textureLod(colortex8, uvw0, 0.0) * (1.0 - lerp) + textureLod(colortex8, uvw1, 0.0) * lerp;
		
			
		

		vec3 scattering = vec3(combined_scattering);
		single_mie_scattering = GetExtrapolatedSingleMieScattering(combined_scattering);

		return scattering;
}



//////////Irradiance Lookup///////////////////////
//////////Irradiance Lookup///////////////////////

vec3 GetIrradiance(
	float r,
	float mu_s
	){
		float x_r = (r - 6360.0) / (6420.0 - 6360.0);
		float x_mu_s = mu_s * 0.5 + 0.5;
		vec2 uv = vec2(GetCombinedTextureCoordFromUnitRange(x_mu_s, 64.0, 512.0),
					   GetCombinedTextureCoordFromUnitRange(x_r, 16.0, 128.0) + 64.0 / 128.0);

		
			return vec3(textureLod(colortex8, vec3(uv, 32.5 / 33.0), 0.0));
		
			
		
}



//////////Rendering///////////////////////////////
//////////Rendering///////////////////////////////


const mat3 LMS = mat3(1.6858, -0.4624, -0.0069, -0.0374, 1.0598, -0.0742, -0.0283, -0.1119, 1.0491);


vec3 GetSunAndSkyIrradiance(
	vec3 point,
	vec3 sun_direction,
	vec3 moon_direction,
	out vec3 moon_irradiance,
	out vec3 sun_sky_irradiance,
	out vec3 moon_sky_irradiance
	){
		float r = length(point);
		float sun_mu_s = dot(point, sun_direction) / r;
		float moon_mu_s = dot(point, moon_direction) / r;

		sun_sky_irradiance = GetIrradiance(r, sun_mu_s) * LMS;
		moon_sky_irradiance = GetIrradiance(r, moon_mu_s) * 0.0003 * LMS;

		vec3 sun_irradiance = vec3(1.68194, 1.85149, 1.91198);
		moon_irradiance = sun_irradiance * GetTransmittanceToSun(r, moon_mu_s) * 0.0003 * LMS;
		sun_irradiance *= GetTransmittanceToSun(r, sun_mu_s);

		return sun_irradiance * LMS;
}


vec3 GetSkyRadiance(
	vec3 camera,
	vec3 view_ray,
	vec3 sun_direction,
	vec3 moon_direction,
	bool horizon,
	out vec3 transmittance,
	out bool ray_r_mu_intersects_ground
	){
		float r = length(camera);
		float rmu = dot(camera, view_ray);
		float distance_to_top_atmosphere_boundary = -rmu - sqrt(rmu * rmu - r * r + 6420.0 * 6420.0);

		if (distance_to_top_atmosphere_boundary > 0.0){
			camera = camera + view_ray * distance_to_top_atmosphere_boundary;
			r = 6420.0;
			rmu += distance_to_top_atmosphere_boundary;
		} else if (r > 6420.0) {
			transmittance = vec3(1.0);
			return vec3(0.0);
		}

		float mu = rmu / r;
		float sun_mu_s = dot(camera, sun_direction) / r;
		float sun_nu = dot(view_ray, sun_direction);

		float moon_mu_s = dot(camera, moon_direction) / r;
		float moon_nu = dot(view_ray, moon_direction);


		ray_r_mu_intersects_ground = RayIntersectsGround(r, mu);


		transmittance = ray_r_mu_intersects_ground ? vec3(0.0) : GetTransmittanceToTopAtmosphereBoundary(r, mu);

		vec3 sun_single_mie_scattering;
		vec3 sun_scattering;

		vec3 moon_single_mie_scattering;
		vec3 moon_scattering;

		horizon = horizon && ray_r_mu_intersects_ground;

		sun_scattering = GetCombinedScattering(r, mu, sun_mu_s, sun_nu, horizon, sun_single_mie_scattering);
		moon_scattering = GetCombinedScattering(r, mu, moon_mu_s, moon_nu, horizon, moon_single_mie_scattering);


		vec3 groundDiffuse = vec3(0.0);
		
		
			

			
			
			

			
			
			

			
			

			
		
		


		vec3 rayleigh = sun_scattering * RayleighPhaseFunction(sun_nu)
					 + moon_scattering * RayleighPhaseFunction(moon_nu) * 0.0003;

		vec3 mie = sun_single_mie_scattering * MiePhaseFunction(0.8, sun_nu)
				+ moon_single_mie_scattering * MiePhaseFunction(0.8, moon_nu) * 0.0003;

		rayleigh = mix(rayleigh,  vec3(dot(rayleigh, vec3(0.2125, 0.7154, 0.0721))) * vec3(1.68194, 1.85149, 1.91198), wetness * 0.5);

		return (rayleigh + mie + groundDiffuse) * (1.0 - wetness * 0.4) * LMS;
}


vec3 GetSkyRadianceToPoint(
	vec3 camera,
	vec3 point,
	vec3 sun_direction,
	vec3 moon_direction,
	out vec3 transmittance
	){
		vec3 view_ray = normalize(point - camera);
		float r = length(camera);
		float rmu = dot(camera, view_ray);
		float distance_to_top_atmosphere_boundary = -rmu - sqrt(rmu * rmu - r * r + 6420.0 * 6420.0);

		if (distance_to_top_atmosphere_boundary > 0.0){
			camera = camera + view_ray * distance_to_top_atmosphere_boundary;
			r = 6420.0;
			rmu += distance_to_top_atmosphere_boundary;
		}

		float mu = rmu / r;
		float sun_mu_s = dot(camera, sun_direction) / r;
		float sun_nu = dot(view_ray, sun_direction);
		float moon_mu_s = dot(camera, moon_direction) / r;
		float moon_nu = dot(view_ray, moon_direction);
		float d = length(point - camera);

		
			
		
			bool ray_r_mu_intersects_ground = false;
		

		transmittance = GetTransmittance(r, mu, d, ray_r_mu_intersects_ground);

		vec3 sun_single_mie_scattering;
		vec3 sun_scattering = GetCombinedScattering(r, mu, sun_mu_s, sun_nu, ray_r_mu_intersects_ground, sun_single_mie_scattering);
		vec3 moon_single_mie_scattering;
		vec3 moon_scattering = GetCombinedScattering(r, mu, moon_mu_s, moon_nu, ray_r_mu_intersects_ground, moon_single_mie_scattering);

		float r_p = ClampRadius(sqrt(d * d + 2.0 * r * mu * d + r * r));
		float mu_p = (r * mu + d) / r_p;
		float sun_mu_s_p = (r * sun_mu_s + d * sun_nu) / r_p;
		float moon_mu_s_p = (r * moon_mu_s + d * moon_nu) / r_p;

		vec3 sun_single_mie_scattering_p;
		vec3 sun_scattering_p = GetCombinedScattering(r_p, mu_p, sun_mu_s_p, sun_nu, ray_r_mu_intersects_ground, sun_single_mie_scattering_p);
		vec3 moon_single_mie_scattering_p;
		vec3 moon_scattering_p = GetCombinedScattering(r_p, mu_p, moon_mu_s_p, moon_nu, ray_r_mu_intersects_ground, moon_single_mie_scattering_p);

		sun_scattering = sun_scattering - transmittance * sun_scattering_p;
		sun_single_mie_scattering = sun_single_mie_scattering - transmittance * sun_single_mie_scattering_p;
		moon_scattering = moon_scattering - transmittance * moon_scattering_p;
		moon_single_mie_scattering = moon_single_mie_scattering - transmittance * moon_single_mie_scattering_p;

		sun_single_mie_scattering = sun_single_mie_scattering * smoothstep(0.0, 0.01, sun_mu_s);
		moon_single_mie_scattering = moon_single_mie_scattering * smoothstep(0.0, 0.01, moon_mu_s);

		vec3 rayleigh = sun_scattering * RayleighPhaseFunction(sun_nu)
					 + moon_scattering * RayleighPhaseFunction(moon_nu) * 0.0003;

		vec3 mie = sun_single_mie_scattering * MiePhaseFunction(0.8, sun_nu)
				+ moon_single_mie_scattering * MiePhaseFunction(0.8, moon_nu) * 0.0003;

		rayleigh = mix(rayleigh, vec3(dot(rayleigh, vec3(0.2125, 0.7154, 0.0721))) * vec3(1.68194, 1.85149, 1.91198), wetness * 0.5);

		return (rayleigh + mie) * (1.0 - wetness * 0.4) * LMS;
}


vec3 BlockLighting(float lightmap, vec3 ao, MaterialMask mask){
	ao = mix(ao, vec3(1.0), clamp(lightmap * 5.0 - 4.2, 0.0, 1.0));

	float lightSourceMask = clamp(mask.glowstone + mask.torch + mask.fire + mask.lava + mask.entitiesLitHigh + mask.entitiesLitMedium + mask.entitiesLitLow + mask.particlelit + mask.soulFire + mask.amethyst, 0.0, 1.0);

	lightmap = min(lightmap, 1.0 - 0.13 * lightSourceMask);

	lightmap = CurveBlockLightTorch(lightmap);

	
		return colorTorchlight * ao * (lightmap * 1.0 * 0.015);
	
		
	
}

vec3 TextureLighting(vec3 albedo, float lightmap, float emissiveness, MaterialMask mask){
	emissiveness *= 1.0;

	
		
			
		
			
		
	
		float blockLightingMask  = 	mask.glowstone 			* 0.016;
			  blockLightingMask += 	mask.torch 				* 0.03;
			  blockLightingMask += 	mask.fire 				* 0.004;
			  blockLightingMask += 	mask.lava 				* 0.012;
			  blockLightingMask += 	mask.redstoneTorch 		* 0.001;
			  blockLightingMask += 	mask.oxidizedBulb 		* 0.0002;

		vec3 blockLighting = vec3(1.0);
		if (blockLightingMask > 0.0) blockLighting = colorTorchlight;

			  blockLightingMask += 	mask.soulFire 			* 0.001;
			  blockLightingMask += 	mask.amethyst 			* 0.0003;
			  blockLightingMask += 	mask.endPortal 			* 4.0;
			  blockLightingMask += 	mask.entitiesLitHigh 	* 0.02;
			  blockLightingMask += 	mask.entitiesLitMedium 	* 0.005;
			  blockLightingMask += 	mask.entitiesLitLow 	* 0.002;
			  blockLightingMask += 	mask.particlelit 		* 0.01;
			//blockLightingMask += 	mask.eyes 				* 0.001;
			  blockLightingMask += 	mask.redstone 			* 0.2 * emissiveness;

		blockLightingMask *= length(albedo);

		
			
				return blockLighting * (max(blockLightingMask * 1.0, emissiveness * 0.005) * 1.0);
			
				
			
		
			
				
			
				
			
		
	
}


float spotShape(float r){
	float shape = clamp(r * 2.0 -(2.0 - 0.25), 0.0, 1.0) * clamp(r * 2.0 -(2.0 - 0.25), 0.0, 1.0) * (3.0 - 2.0 * clamp(r * 2.0 -(2.0 - 0.25), 0.0, 1.0));
	shape += clamp(r * 1.25 -(1.0 - 0.25), 0.0, 1.0) * clamp(r * 1.25 -(1.0 - 0.25), 0.0, 1.0) * (3.0 - 2.0 * clamp(r * 1.25 -(1.0 - 0.25), 0.0, 1.0)) * 0.01;

	const float ringSize = 8.0 / 0.25;
	shape *= 1.0 - clamp(1.0 - abs(r * ringSize - ringSize + 1.0), 0.0, 1.0) * clamp(1.0 - abs(r * ringSize - ringSize + 1.0), 0.0, 1.0) * (3.0 - 2.0 * clamp(1.0 - abs(r * ringSize - ringSize + 1.0), 0.0, 1.0)) * 0.25;

	return shape;
}


float TorchScreenSpaceShadow(vec3 viewPos, vec3 viewDir, vec3 normal, vec3 shadowDir){
	shadowDir.z = max(shadowDir.z, 1e-5);

	float rayLength = -0.04 * viewPos.z / shadowDir.z;
	vec3 viewRayDir = shadowDir * rayLength;

	vec3 start = viewPos;

	float pixelScale = max(pixelSize.x, pixelSize.y);
	float NdotL = clamp(dot(shadowDir, normal), 0.0, 1.0);

	float fov = atan(1.0 / gbufferProjection[1][1]) * 360.0 * 0.31830988618;
	start += viewRayDir * (pixelScale * max(0.04 / max(dot(normal, -viewDir), 0.1), 1e3));
	start += normal * (8e-3 * fov * -viewPos.z * pixelScale / max(NdotL, 0.01));

	vec3 end = start + viewRayDir;

	start = vec3(vec2(gbufferProjection[0][0], gbufferProjection[1][1]) * start.xy, -start.z);
	end   = vec3(vec2(gbufferProjection[0][0], gbufferProjection[1][1]) * end.xy,   -end.z);

	vec3 screenRayDir = (end - start);
	screenRayDir.xy *= 0.5;

	start.xy += gbufferProjection[3].xy;
	start.xy *= 0.5;

	
		float noise = BlueNoiseTemproal().x;
		vec2 offsetCoord = 0.5 + taaJitter * 0.5;
	
		
		
	

	float minDist = LinearDepth_From_ScreenDepth(0.7);

	float shadow = 1.0;
	float stepLength = 1.0;

	for (int i = 0; i < 8; i++, start += screenRayDir * stepLength, stepLength += 0.3){
		vec3 samplePos = start + screenRayDir * noise * stepLength;

		if (samplePos.z < minDist) break;

		samplePos.xy = samplePos.xy / samplePos.z + offsetCoord;
		
		if (clamp(samplePos.xy, 0.0, 1.0) != samplePos.xy) break;

		float sampleDepth = textureLod(depthtex1, samplePos.xy, 0.0).x;

		if (sampleDepth < 0.7) break;

		
				

			
				
				
			
				
			
		
			float sampleDist = LinearDepth_From_ScreenDepth(textureLod(depthtex1, samplePos.xy, 0.0).x);
		

		if (samplePos.z - sampleDist > 0.0){
			shadow = 0.0;
			break;
		}
	}

	return shadow * 0.85 + 0.15;
}

vec3 HeldLighting(vec3 viewPos, vec3 viewDir, vec3 normal, float roughness, vec3 ao, bool isHand){
	float heldLightFalloff = 0.0;

	
		
		
			
		
			
				
				
							   
							   

				
				
							   
							   

				
			
				
			

			
				
				
				

				
				

				
					
				
				
				
				
			

			
				
				
				

				
				

				
					
				
				

				
			
		
		
		
			
		
			
		

	

		if (isHand){
			heldLightFalloff = 0.2 * max(heldBlockLightValue, heldBlockLightValue2);
		}else{
			

				if (heldBlockLightValue > 0.0){
					vec3 torchPosR = viewPos - vec3(0.25, -0.15, 0.0);
					float torchDistR = length(torchPosR);
					torchDistR += 4.0 / (torchDistR + 2.0);

					float torchR = pow(torchDistR, -1.8);

					vec3 shadowDirR = -normalize(viewPos - vec3(0.25, -0.15, 0.0));
					torchR *= TorchScreenSpaceShadow(viewPos, viewDir, normal, shadowDirR);
					torchR *= Fd_Burley(normal, -viewDir, shadowDirR, roughness) * 1.7 + 0.05;

					heldLightFalloff += heldBlockLightValue * torchR;
				}

				if (heldBlockLightValue2 > 0.0){
					vec3 torchPosL = viewPos - vec3(-0.25, -0.15, 0.0);
					float torchDistL = length(torchPosL);
					torchDistL += 4.0 / (torchDistL + 2.0);

					float torchL = pow(torchDistL, -1.8);

					vec3 shadowDirL = -normalize(viewPos - vec3(-0.25, -0.15, 0.0));
					torchL *= TorchScreenSpaceShadow(viewPos, viewDir, normal, shadowDirL);
					torchL *= Fd_Burley(normal, -viewDir, shadowDirL, roughness) * 1.7 + 0.05;

					heldLightFalloff += heldBlockLightValue2 * torchL;
				}

			

				
				
				
				

				

			
		}

		
			return colorTorchlight * (ao * 0.5 + 0.5) * (heldLightFalloff * (1.0 * 1.0 * 0.0015));
		
			
		

	

}


void TorchSpecularHighlight(inout vec3 color, vec3 viewPos, vec3 viewDir, float dist, vec3 albedo, vec3 normal, Material material){
	material.roughness = max(material.roughness, 0.002);
	
		
			
			
							
							

			
			
							
							

			
		
			
		

		
		
		
			
			
			

			
			

			
			
			
		

		
			
			
			

			
			

			

			
		

		
			
				
			
				
			
		
	
		float heldHighlight = SpecularGGX(normal, -viewDir, -viewDir, material.roughness, material.f0);

		if (heldHighlight > 0.001){
			dist += 4.0 / (dist + 2.0);
			heldHighlight *= pow(dist, -1.8);

			heldHighlight *= (heldBlockLightValue + heldBlockLightValue2);

			
				color += colorTorchlight * albedo * (heldHighlight * (1.0 * 1.0 * 0.0002));
			
				
			
		}
	
}


vec3 VariablePenumbraShadow(vec3 worldPos, vec3 worldNormal, float sunlight, vec3 albedo, float scatteringStrength, MaterialMask mask, out vec3 sss){
	worldPos += gbufferModelViewInverse[3].xyz;
	worldPos -= gbufferModelViewInverse[2].xyz * 0.5 * mask.hand;

	
		
		

		
	
		vec3 shadowNormal = mat3(shadowModelView) * worldNormal;
		shadowNormal.z = -shadowNormal.z;

		vec3 shadowScreenPos = mat3(shadowModelView) * worldPos + shadowModelView[3].xyz;
	
	shadowScreenPos *= vec3(shadowProjection[0][0], shadowProjection[0][0], -shadowProjection[0][0] * 0.5);

	float zScale = 1.0 / shadowProjection[0][0];
	float dist = length(shadowScreenPos.xy);
	float distortFactor = (1.0 - 0.9) + dist * 0.9;


	
		vec2 noise = BlueNoiseTemproal();
		
			
		
			
				
			
			
				
			
		
	
		
	

	const mat2 rotMat = mat2(cos(2.39996322973), sin(2.39996322973), -sin(2.39996322973), cos(2.39996322973));

	vec3 result = vec3(1.0);

	sss = vec3(0.0);

	if (scatteringStrength > 0.0){
		vec3 shadowScreenPosRaw = shadowScreenPos;
		shadowScreenPosRaw.xy *= 0.95 / distortFactor;
		shadowScreenPosRaw = shadowScreenPosRaw * 0.5 + 0.5;

		float spread = (scatteringStrength * 0.12 + 0.05) / (distortFactor * shadowDistance) + 1.0 / shadowMapResolution;
		vec3 scatteringDensity = 15.0 / (scatteringStrength * albedo + 0.1);

		const float steps = 6;
		const float rSteps = 1.0 / steps;

		float angle = noise.x * 6.28318530718;
		vec2 rot = vec2(cos(angle), sin(angle));

		float scatteringDepth = 0.0;

		for (float i = 0.0; i < steps; i++){
			rot *= rotMat;
			float radius = (i + noise.y) * rSteps;
			vec2 offset = rot * spread * radius;

			float sampleDepth = textureLod(shadowtex0, shadowScreenPosRaw.xy + offset, 0.0).x;

			scatteringDepth += max(shadowScreenPosRaw.z - sampleDepth, 1e-5);
		}
		scatteringDepth *= rSteps * zScale;

		vec3 scattering = exp2(-scatteringDepth * scatteringDensity);

		float distFalloff = clamp(((min(shadowDistance, far) - length(worldPos)) * 0.05 - 1.0), 0.0, 1.0);

		
			
		
			sss = scattering * (1.0 * distFalloff * 0.2);
		

		scatteringStrength *= distFalloff; 
	}

	if (sunlight > 0.0){
		shadowScreenPos += shadowNormal * (0.002 * (1.0 - mask.leaves) * distortFactor);
		shadowScreenPos.xy *= 0.95 / distortFactor;
		shadowScreenPos = shadowScreenPos * 0.5 + 0.5;

		if (clamp(shadowScreenPos.xy, 0.0, 1.0) == shadowScreenPos.xy){
			result = vec3(0.0);

			float spread = 0.1 * 200.0 / (distortFactor * shadowDistance);

			float avgDiff = 0.0;

			
				float spreadLod = log2(shadowMapResolution / 512.0);
				for (int i = -1; i <= 1; i++){
					for (int j = -1; j <= 1; j++){
						vec2 lookupCoord = shadowScreenPos.xy + vec2(i, j) * spread * 0.0039;
						float depthDiff = shadowScreenPos.z - textureLod(shadowtex0, lookupCoord, spreadLod).x;
						depthDiff = clamp(depthDiff * zScale * 0.0035, 0.0, 0.025);
						avgDiff += depthDiff * depthDiff;
					}
				}
				
				avgDiff /= 9.0;
				avgDiff = sqrt(avgDiff);
			

			avgDiff = max(avgDiff, 0.015 * mask.leaves + 0.01 * mask.grass);


			float sampleSpread = avgDiff * 0.2 * spread + 1.0 / shadowMapResolution;

			shadowScreenPos.z -= (2e-6 + noise.y * 2e-6) * (dist + 0.03) * (1.0 - 0.9 * mask.leaves) * shadowDistance;


			const float steps = 16;
			const float rSteps = 1.0 / steps;

			float angle = noise.y * 6.28318530718;
			vec2 rot = vec2(cos(angle), sin(angle));

			for (float i = 0.0; i < steps; i++){
				rot *= rotMat;
				float radius = sqrt((i + noise.x) * rSteps);
				vec2 offset = rot * radius * sampleSpread;

				vec3 sampleCoord = vec3(shadowScreenPos.xy + offset, shadowScreenPos.z);

				
					float translucentShadow = step(sampleCoord.z, textureLod(shadowtex0, sampleCoord.xy, 0.0).x);
					result += vec3(translucentShadow);

					float soildShadow = textureLod(shadowtex1, sampleCoord, 0.0);
					vec3 shadowColorSample = GammaToLinear(textureLod(shadowcolor0, sampleCoord.xy, 0.0).rgb);
					result += shadowColorSample * (soildShadow - translucentShadow);
				
					
					
				
			}
			result *= rSteps;
		}
	}

	
		
	
		return result * (clamp(0.5 - scatteringStrength * 0.5, 0.0, 1.0) + 0.5);
	
}

float ScreenSpaceShadow(vec3 viewPos, vec3 viewDir, vec3 normal, MaterialMask mask){
	mask.leaves = clamp(mask.leaves * 1e10, 0.0, 1.0);

	float fov = mask.grass + mask.leaves > 0.5 ? 95.0 : atan(1.0 / gbufferProjection[1][1]) * 360.0 * 0.31830988618;

	vec3 viewRayDir = shadowVector * max(-viewPos.z * 3e-5 * fov, 0.06 * shadowDistance / shadowMapResolution);

	vec3 start = viewPos;

	if (mask.grass + mask.leaves < 0.1){
		float pixelScale = max(pixelSize.x, pixelSize.y);
		float NdotL = clamp(dot(shadowVector, normal), 0.0, 1.0);

		start += viewRayDir * (pixelScale * max(0.04 / max(dot(normal, -viewDir), 0.1), 1e3));
		start += normal * (8e-3 * fov * -viewPos.z * pixelScale / max(NdotL, 0.01));
	}

	vec3 end = start + viewRayDir;

	start = vec3(vec2(gbufferProjection[0][0], gbufferProjection[1][1]) * start.xy, -start.z);
    end   = vec3(vec2(gbufferProjection[0][0], gbufferProjection[1][1]) * end.xy,   -end.z);

	vec3 screenRayDir = (end - start);
	screenRayDir.xy *= 0.5;

	start.xy += gbufferProjection[3].xy;
	start.xy *= 0.5;

	start += screenRayDir * mask.grass;

	float absorption = 0.0;
	absorption += 0.7 * mask.grass;
	absorption += 0.85 * mask.leaves;
	absorption = pow(absorption, sqrt(-viewPos.z) * 0.5);

	
		float noise = BlueNoiseTemproal().x;
		
			
		
		vec2 offsetCoord = 0.5 + taaJitter * 0.5;
	
		
		
	

	float shadow = 1.0;
	float stepLength = 1.0;
	float zThickness = 0.025 + 0.0125 * -viewPos.z;

	for (int i = 0; i < 12; i++, start += screenRayDir * stepLength, stepLength += 0.3){
		vec3 samplePos = start + screenRayDir * noise * stepLength;
		samplePos.xy = samplePos.xy / samplePos.z + offsetCoord;
		
		if (clamp(samplePos.xy, 0.0, 1.0) != samplePos.xy) break;

		
			
			

			
				
				
			
				
			
		
			float sampleDist = LinearDepth_From_ScreenDepth(textureLod(depthtex1, samplePos.xy, 0.0).x);
		

		float depthDiff = samplePos.z - sampleDist;

		if (depthDiff > 0.0 && depthDiff < zThickness) shadow *= absorption;

		if (shadow < 0.01) break;
	}

	return shadow;
}

vec3 GetWavesNormalFromTex(vec3 position){
	const float maxCausticsNormalHeight = 500.0;

	vec2 coord = position.xz;
	vec3 lightVector = refract(worldShadowVector, vec3(0.0, 1.0, 0.0), 1.0 / 1.2);
	coord.x += position.y * lightVector.x / lightVector.y;
	coord.y += position.y * lightVector.z / lightVector.y;

	coord *= 0.02;
	coord = fract(coord);

	coord *= pixelSize * min(screenSize.y, maxCausticsNormalHeight);

	vec3 normal;
	normal.xyz = DecodeNormal(textureLod(colortex7, coord, 0.0).xy);

	return normal;
}

float CalculateWaterCaustics(vec3 worldPos){
	worldPos.xyz += cameraPosition;

	vec2 dither = BlueNoiseTemproal();

	vec3 lookupCenter = worldPos + vec3(0.0, 1.0, 0.0);

	vec3 lightVector = refract(worldShadowVector, vec3(0.0, -1.0, 0.0), 1.0 / 1.2);
	vec3 depthBias = vec3(worldPos.y * lightVector.x, 0.0, worldPos.y * lightVector.z) / lightVector.y;


	float caustics = 0.0;

	for (float i = -1.0; i <= 1.0; i++){
		for (float j = -1.0; j <= 1.0; j++){
			vec2 offset = dither + vec2(i, j);

			vec3 lookupPoint = lookupCenter;
			lookupPoint.xz += offset * 0.1;

			vec3 wavesNormal = GetWavesNormalFromTex(lookupPoint).xzy;

			vec3 refractVector = refract(vec3(0.0, 1.0, 0.0), wavesNormal.xyz, 1.0);
			vec3 collisionPoint = lookupPoint - refractVector / refractVector.y;
			collisionPoint -= worldPos;

			float dist = dot(collisionPoint, collisionPoint) * 7.1;

			caustics += 1.0 - clamp(dist * 7.0, 0.0, 1.0);
		}
	}

	return mix(caustics * 0.16 + 0.2, 1.0, 0.3 - 0.3 * float(isEyeInWater));
}



float GroundTruthBasedAmbientOcclusion(vec3 viewPos, vec3 viewDir, vec3 normal){
	
		vec2 noise = BlueNoiseTemproal();
	
		
	

	const float steps = 3;
	const float rSteps = 1.0 / steps;

	const float sliceSteps = 3;
	const float rSliceSteps = 1.0 / sliceSteps;



	const float falloffStart = 0.7;
	const float maxSampleRadius = 0.1;
	
		
	

	float projFactor = 0.125 * gbufferProjection[1][1];

	
		
			
		
			float radius = 2.0 - viewPos.z * 0.01;
		

		float sampleRadiusRaw = radius * projFactor / -viewPos.z;
		float sampleRadius = min(sampleRadiusRaw, maxSampleRadius);
		float falloff = sampleRadius * radius / sampleRadiusRaw;
	
		
		
	

	float ao = 0.0;

	for (float i = 0.0; i < steps; i++){
		float sliceAngle = (i + noise.x) * rSteps * 3.14159265359;

		vec3 sliceDir = vec3(cos(sliceAngle), sin(sliceAngle), 0.0);
		vec3 orthoSliceDir = sliceDir - dot(sliceDir, viewDir) * viewDir;
		vec3 axis = cross(sliceDir, viewDir);
		vec3 projNormal = normal - dot(normal, axis) * axis;
		
		float rProjNormalLength = inversesqrt(dot(projNormal, projNormal));

		float cosNormalAngle = clamp(dot(projNormal, viewDir) * rProjNormalLength, 0.0, 1.0);
		float normalAngle = sign(dot(orthoSliceDir, projNormal)) * facos(cosNormalAngle);

		vec2 minCosHorizonAngle = cos(vec2(normalAngle + 1.57079632679, normalAngle - 1.57079632679));

		vec2 maxCosHorizonAngle = minCosHorizonAngle;

		for (float j = 0.0; j < sliceSteps; j++){
			float stepNoise = (i + j * sliceSteps) * 0.61803399;
			stepNoise = fract(stepNoise + noise.y);

			float offset = (j + stepNoise) * rSliceSteps;
			offset *= offset;
			vec2 sampleOffset = offset * sliceDir.xy * sampleRadius;
			sampleOffset.y *= aspectRatio;

			{
				vec2 sampleCoord = texCoord + sampleOffset;
				float sampleDepth = textureLod(depthtex1, sampleCoord, 0.0).x;
				
					
					
						
						
					
						
					
					
				
					vec3 sampleVector = ViewPos_From_ScreenPos(sampleCoord, sampleDepth) - viewPos;
				

				float rSampleDist = inversesqrt(dot(sampleVector, sampleVector));

				float cosHorizonAngle = dot(sampleVector, viewDir) * rSampleDist;

				
					
					
				
					float falloffWeight = remapSaturate(1.0 / rSampleDist, falloff * falloffStart, falloff);
				
				cosHorizonAngle = mix(cosHorizonAngle, minCosHorizonAngle.x, falloffWeight);

				maxCosHorizonAngle.x = max(cosHorizonAngle, maxCosHorizonAngle.x);
			}{
				vec2 sampleCoord = texCoord - sampleOffset;
				float sampleDepth = textureLod(depthtex1, sampleCoord, 0.0).x;
				
					
					
						
						
					
						
					
					
				
					vec3 sampleVector = ViewPos_From_ScreenPos(sampleCoord, sampleDepth) - viewPos;
				

				float rSampleDist = inversesqrt(dot(sampleVector, sampleVector));

				float cosHorizonAngle = dot(sampleVector, viewDir) * rSampleDist;
				
				
					
					
				
					float falloffWeight = remapSaturate(1.0 / rSampleDist, falloff * falloffStart, falloff);
				
				cosHorizonAngle = mix(cosHorizonAngle, minCosHorizonAngle.y, falloffWeight);

				maxCosHorizonAngle.y = max(cosHorizonAngle, maxCosHorizonAngle.y);
			}
		}
		maxCosHorizonAngle = vec2(-facos(maxCosHorizonAngle.y), facos(maxCosHorizonAngle.x)) * 2.0;

		float horizonAngleIntegra = 2.0 * cosNormalAngle + (maxCosHorizonAngle.x + maxCosHorizonAngle.y) * sin(normalAngle) - cos(maxCosHorizonAngle.x - normalAngle) - cos(maxCosHorizonAngle.y - normalAngle);

		ao += horizonAngleIntegra / rProjNormalLength;
	}
	ao *= 0.25 * rSteps;

	return clamp(mix(1.0, ao, 0.95), 0.0, 1.0);
}

vec3 GTAOMultiBounce(float ao, vec3 albedo){
	vec3 a =  2.0404 * albedo - 0.3324;
	vec3 b = -4.7951 * albedo + 0.6417;
	vec3 c =  2.7552 * albedo + 0.6903;

	return max(vec3(ao), ((ao * a + b) * ao + c) * ao);
}


float BicubicBlurTexture(sampler2D texSampler, vec2 coord, vec2 texSize){
	vec2 texPixelSize = 1.0 / texSize;
	coord = coord * texSize - 0.5;

	vec2 p = floor(coord);
	vec2 f = coord - p;

	vec2 ff = f * f;
	vec4 w0;
	vec4 w1;
	w0.xz = 1.0 - f; w0.xz *= w0.xz * w0.xz;
	w1.yw = ff * f;
	w1.xz = 3.0 * w1.yw + 4.0 - 6.0 * ff;
	w0.yw = 6.0 - w1.xz - w1.yw - w0.xz;

	vec4 s = w0 + w1;
	vec4 c = p.xxyy + vec2(-0.5, 1.5).xyxy + w1 / s;
	c *= texPixelSize.xxyy;

	vec2 m = s.xz / (s.xz + s.yw);
	return mix(mix(textureLod(texSampler, c.yw, 0.0).x, textureLod(texSampler, c.xw, 0.0).x, m.x),
			   mix(textureLod(texSampler, c.yz, 0.0).x, textureLod(texSampler, c.xz, 0.0).x, m.x),
			   m.y);
}

float CloudShadowFromTex(vec3 worldPos){
	
		
	

	vec2 coord = worldPos.xz - worldShadowVector.xz * ((worldPos.y + cameraPosition.y) / worldShadowVector.y);
	coord = coord * (1.0 / 4096.0) + 0.5;

	float cloudShadow = 1.0 - wetness;

	if (clamp(coord, 0.0, 1.0) == coord){
		float shadowTexSize = floor(min(screenSize.y * 0.45, 256.0));
		vec2 shadowTexel = screenSize - coord * shadowTexSize;

		vec2 fade = clamp(5.9 - abs(coord - 0.5) * 12.0, 0.0, 1.0);
		
		cloudShadow = mix(cloudShadow, BicubicBlurTexture(colortex2, shadowTexel * pixelSize, screenSize), fade.x * fade.y);
	}

	
		float fadeAngle = smoothstep(0.06, 0.18, abs(worldShadowVector.y));
		cloudShadow = mix(1.0 - wetness, cloudShadow, fadeAngle * fadeAngle);
	

	return ((1.0 - 1.0) * wetness + 1.0) * cloudShadow + (wetness - 1.0 * wetness); // mix(cloudShadow, mix(1.0, cloudShadow, RAIN_SHADOW), wetness)
}

float GetSmoothCloudShadow(){
	
		float globalCloudShadow = mix(texelFetch(colortex2, ivec2(40, screenSize.y - 1.0), 0).a, 1.0, 0.03 - wetness * 0.015);
	
		
	

	return globalCloudShadow;
}

vec3 SkyLighting(vec3 worldNormal, float lightmap, float cloudShadow){
	float lightmapFalloff = clamp(lightmap * 2.5 - 0.2, 0.0, 1.0);
	lightmapFalloff = lightmapFalloff * lightmapFalloff * (3.0 - 2.0 * lightmapFalloff) * 0.6 + 0.4;

	float SdotN = dot(worldNormal, normalize(worldSunVector + vec3(0.0, 1.0, 0.0))) * lightmapFalloff;
	float MdotN = dot(worldNormal, normalize(worldMoonVector + vec3(0.0, 1.0, 0.0))) * lightmapFalloff;


	vec3 skylight = colorSunSkylight * (SdotN * 0.35 + 0.65);
	skylight += colorMoonSkylight * (MdotN * 0.35 + 0.65);

	vec3 skySunLight = (worldNormal.y * 0.4 * lightmapFalloff + 0.6) * colorShadowlight * 0.025;

	
		skylight += skySunLight;
	
		
	

	
		float coverage = mix(0.28, 0.96, wetness);
		skylight += skySunLight * ((1.0 - wetness) * clamp(coverage * 4.0 - 0.6, 0.0, 1.0));
		
			float LdotN = dot(worldNormal, worldShadowVector) * lightmapFalloff * 0.4 + 0.6;
			skylight += colorShadowlight * (LdotN * (0.05 - 0.05 * cloudShadow) * (1.0 - wetness));
		
	

	skylight = mix(skylight, colorShadowlight * (SdotN * 0.012 + 0.015), wetness * 0.8);

	return skylight * max(float(isEyeInWater == 1) * 0.003, lightmap * 0.2 * 1.0);
}


////////////////////////////// Main //////////////////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////// Main //////////////////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////// Main //////////////////////////////////////////////////////////////////////////////////////////////////////////////////

void main(){
	GbufferData gbuffer 			= GetGbufferData();
	MaterialMask materialMask 		= CalculateMasks(gbuffer.materialIDW);
	MaterialMask materialMaskSoild 	= CalculateMasks(gbuffer.materialIDL);

	compositeOutput1 = texelFetch(colortex1, texelCoord, 0);

//////////////////// Soild /////////////////////////////////////////////////////////////////////////
//////////////////// Soild /////////////////////////////////////////////////////////////////////////

	if (materialMaskSoild.sky < 0.5){
		FixParticleMask(materialMaskSoild, materialMask, gbuffer.depthL, gbuffer.depthW);

		vec3 viewPos 					= ViewPos_From_ScreenPos(texCoord, gbuffer.depthL);

		
			
			
				
				
			
		

		vec3 worldPos					= mat3(gbufferModelViewInverse) * viewPos;

		vec3 viewDir 					= normalize(viewPos);
		vec3 worldDir 					= normalize(worldPos);
		vec3 worldNormal 				= mat3(gbufferModelViewInverse) * gbuffer.normalL;

		
			
		
			float farDist 				= max(far * 1.2, 1024.0);
		
		float opaqueDist 				= gbuffer.depthL < 1.0 ? length(viewPos) : farDist;


		
			
				float cloudShadow = CloudShadowFromTex(worldPos);
			
				
			
		
			
		

		vec3 sunlightMult = colorShadowlight * (1.0 * mix(cloudShadow, 1.0, 0.03 - wetness * 0.015));

		
			
		

		vec3 waterTint = vec3(1.0);

		
			if (isEyeInWater == 1) waterTint = vec3(0.55, 0.75, 1.0) / max(3.0, opaqueDist * 0.1 * 1.0);
			sunlightMult *= waterTint;
		

		
			
		


		gbuffer.material.scattering *= clamp(1.0 - materialMaskSoild.leaves - materialMaskSoild.grass, 0.0, 1.0);

		
			
				
			
				
			
		
			materialMaskSoild.leaves *= clamp(3.2 - opaqueDist / min(shadowDistance, far) * 4.0, 0.0, 1.0) * 0.6 + 0.4;
		

		worldNormal = normalize(mix(worldNormal, vec3(0.0, 1.0, 0.0), materialMaskSoild.grass * 0.49));


		
			
		
			vec3 finalComposite = vec3(0.86, 0.94, 1.19) * ((worldNormal.y * 0.3 + 0.8) * 0.000005);
		
		finalComposite += SkyLighting(worldNormal, gbuffer.lightmapL.g, cloudShadow) * waterTint;

		vec4 gi = compositeOutput1;
		gi = mix(gi, vec4(0.0, 0.0, 0.0, 1.0), materialMask.particle);

		vec3 rsm = CurveToLinear(gi.rgb);
		rsm *= sunlightMult * (8.0 * 1.0);
		
			
				rsm *= clamp(gbuffer.lightmapL.g * 4.0 + float(isEyeInWater == 1), 0.0, 1.0);
			
				
			
		

		
			
		
			
		
			
		
			finalComposite += rsm;

		
			
				vec3 ao = GTAOMultiBounce(gi.a, gbuffer.albedo);
			
				
			
		
			
		
		finalComposite *= ao;
		
		if(heldBlockLightValue + heldBlockLightValue2 > 0.0)
			finalComposite += HeldLighting(viewPos, viewDir, gbuffer.normalL, gbuffer.material.roughness, ao, materialMask.hand > 0.5);

		finalComposite += BlockLighting(gbuffer.lightmapL.r, ao, materialMaskSoild);
		finalComposite += TextureLighting(gbuffer.albedo, gbuffer.lightmapL.r, gbuffer.material.emissiveness, materialMaskSoild);


		float sunlight = Fd_Burley(worldNormal, -worldDir, worldShadowVector, gbuffer.material.roughness);

		float sunlightTrans = clamp(materialMaskSoild.leaves * 3.0, 0.0, 1.0) * 0.25 + materialMask.particle * 0.4 + materialMaskSoild.grass * 0.15;
		sunlight = mix(sunlight, 0.6, sunlightTrans);
		gbuffer.parallaxShadow = clamp(gbuffer.parallaxShadow + sunlightTrans * 1e10, 0.0, 1.0);


		vec3 shadow = vec3(1.0);

		
			float occludedWater = gbuffer.waterMask * materialMask.stainedGlass;
			float lightMask = clamp(gbuffer.lightmapL.g * mix(1e4, 1.0, occludedWater) + float(isEyeInWater == 1), 0.0, 1.0);
			shadow *= lightMask;
		
		
		vec3 specularHighlight = vec3(0.0);
		vec3 sss = vec3(0.0);
	
		if ((sunlight + gbuffer.material.scattering) * shadow.x > 0.0){
			shadow *= VariablePenumbraShadow(worldPos, worldNormal, sunlight, gbuffer.albedo, gbuffer.material.scattering, materialMaskSoild, sss);
			
 				sss *= lightMask;
			
			finalComposite += sss * sunlightMult;
		}

		
			
		

		float metalnessMask = gbuffer.material.metalness;
		float skylightmap = min(smoothstep(0.3, 0.8, gbuffer.lightmapL.g), float(isEyeInWater == 0));
		metalnessMask *= 0.1 * skylightmap + 0.9;
		metalnessMask = 1.0 - metalnessMask * 0.985;

		if (any(greaterThan(sunlight * shadow, vec3(0.0)))){
			
				
					
						
					
						
					
				
					
						
					
						if (materialMaskSoild.leaves + materialMaskSoild.hand + materialMaskSoild.entitiesSnow < 1.0 && gbuffer.parallaxShadow > 0.0)
					
				
					shadow *= mix(ScreenSpaceShadow(viewPos, viewDir, gbuffer.normalL, materialMaskSoild), 1.0, clamp(materialMaskSoild.leaves * 1.67 - 0.67, 0.0, 1.0));
			

			
				if (materialMask.water > 0.5 || isEyeInWater == 1)
					shadow *= CalculateWaterCaustics(worldPos);
			

			shadow *= sunlightMult;
	
			finalComposite += shadow * (sunlight * metalnessMask);

			if (materialMask.water + materialMask.ice < 0.5){
				float highlightGGX = SpecularGGX(gbuffer.normalL, -viewDir, shadowVector, clamp(gbuffer.material.roughness, 0.0015, 0.9), gbuffer.material.f0);
				highlightGGX *= clamp(4.0 - gbuffer.material.roughness * 3.5, 0.0, 1.0);
				highlightGGX *= 0.07 - materialMaskSoild.grass * 0.035;
				specularHighlight = mix(vec3(1.0), gbuffer.albedo, vec3(gbuffer.material.metalness)) * shadow * highlightGGX;
			}
		}

		finalComposite *= gbuffer.albedo;

		finalComposite *= metalnessMask;

		finalComposite += specularHighlight;

		finalComposite += vec3(1.0) * materialMaskSoild.lightning;


		finalComposite /= 2048.0;
		finalComposite = LinearToCurve(finalComposite);
		compositeOutput1 = vec4(finalComposite, 0.0);
	}
}

