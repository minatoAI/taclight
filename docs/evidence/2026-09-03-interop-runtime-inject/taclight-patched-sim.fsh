#version 430 core







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

/* TACLIGHT_PATCH_BEGIN iterationT 3.2.0 runtime */
/* TACLIGHT_PATCH_BEGIN inline-core */
const float TACLIGHT_LIGHT_GAIN = 2.2;
// ============================================================================
// TacLight Shaders · 零依赖数学层(lib/taclight_math.glsl)
// 仅公开数学公式,无 uniform、无内建状态、无包私有编码 —— 照明核心
// (taclight_core.glsl)与风格层(taclight_style.glsl)共同的下层。
// 版本无关(#version 120 的 final 与 #version 430 的 composite 家族均可 include)。
// ============================================================================


// Interleaved Gradient Noise(Jimenez 2014 公开公式):时序稳定的空间抖动源,
// 用于 SSO/体积光步进抖动与胶片颗粒。
float taclight_ign(vec2 p) {
    return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715))));
}

// M3 · HG 相位函数(Henyey-Greenstein,公开数学):体积散射角分布。
// cosTheta = 视线方向 · 光传播方向;g>0 前向散射强(手电束感),g=0 各向同性。
float taclight_hg(float cosTheta, float g) {
    float g2 = g * g;
    return (1.0 - g2) / (12.566371 * pow(1.0 + g2 - 2.0 * g * cosTheta, 1.5));
}


// ============================================================================
// TacLight Shaders · 零依赖照明核心(lib/taclight_core.glsl)
// ============================================================================
// 【分层契约 —— interop 核心剥离(v1.0)】
//   本文件 = TacLight 照明核心,是跨光影包移植的"唯一需要搬运的代码"。
//   依赖边界(由 ShaderCoreContract 契约钉死):
//     · 仅依赖 Iris/Oculus 标准 uniform(gbufferModelView 系列/cameraPosition/
//       depthtex1)与模组绑定的 SSBO binding=7;
//     · 仅 #include lib/taclight_math.glsl(公开数学,零依赖);
//     · **禁止出现 colortex 字面量** —— G-Buffer 私有编码(法线/albedo/材质/
//       遮挡系数布局)一律在 taclight_adapter.glsl(包侧)或消费 pass 中;
//     · 唯一的注入点:遮挡系数采样宏 taclight_occlusion_at(uv),未适配的包
//       回退默认值 1.0(保守全挡:宁可误挡不可漏光)。
//   亮度量纲:TACLIGHT_LIGHT_GAIN 等对本包 tonemap 链的标定常数在 adapter,
//   不在本文件 —— 移植到其他包后需随对方管线重新标定。
//
// 【SSBO 契约镜像】唯一真源(Single Source):
//   taclight/src/main/java/dev/taclight/channel/SpotlightBufferLayout.java
//   守护:SpotlightBufferLayoutContract + UploaderSemanticContract(纯 JVM 契约)
//
// 【坐标语义 —— doc06 §2.5 铁律 3,v0.9.0 起生效】
//   posRadius.xyz / dirType.xyz 均为 **world** 坐标系;
//   scene-relative 转换只允许发生在消费侧,且必须经过下方
//   taclight_world_to_scene -> taclight_scene_to_view 两级封装。
//   历史教训:直接把 world 乘进 composite 的 gbufferModelView(R-only,
//   无平移)= 灯被摆到几百格外(v0.8.4 事故)。本文件其余代码禁止再出现
//   内联的坐标换算。
//
// 仅可被 #version 430 及以上的程序 include(composite* 等);gbuffers 用不到它。
// ============================================================================



// ---- 头部 flags 位(与 SpotlightBufferLayout.java 逐位一致)----
const uint TACLIGHT_FLAG_HAS_DATA = 1u; // bit0
const uint TACLIGHT_FLAG_DEBUG = 2u; // bit1 K 键绿锥调试(doc06 §2.10)
const uint TACLIGHT_FLAG_TIMING_PROBE = 8u; // bit3 reserved 回读探针(DEBUG 构建才置位)

// ---- 每灯 96B · 6×vec4(std430,与 Java writeLight 写序一致)----
struct TacLightSpot {
    vec4 posRadius;       // xyz = **world** 坐标;w = 半径(格)
    vec4 colorIntensity;  // rgb = 线性色;a = 强度
    vec4 dirType;         // xyz = 归一化 world 方向(灯→目标);w = 类型(1=spot)
    vec4 cone;            // x = cos 外锥半角;y = cos 内锥半角;z/w 保留
    vec4 vlParams;        // x 各向异性 g,y 密度,z 光束强度(M3 体积光用)
    vec4 cookie;          // GLSL→Java 回写诊断槽位(探针阶段启用)
};

// 注意(铁律 2):不要在 shaders.properties 里声明 bufferObject.7 ——
// 那会让 Iris 自建同名缓冲覆盖模组绑定。此缓冲由模组创建并每帧更新,包只读。
// v0.12(09-01 深夜④):lights 改定长 [8],尾段并入体素遮挡栅格
// (VoxelField/VoxelGrid 每 tick 填充;taclight_vox_transmit DDA 消费)。
layout(std430, binding = 7) buffer TacLightSSBO {
    uint  lightCount;     // 头偏移 0
    float vlIntensity;    // 头偏移 4
    uint  flags;          // 头偏移 8
    uint  reserved;       // 头偏移 12(时序探针回写字)
    TacLightSpot lights[8];   // 16..783(定长;Java 侧 clamp 8 同源)
    vec4  voxOrigin;      // 784: xyz=栅格角点 world(方块格对齐) w>0=有效/w<=0=无效
    ivec4 voxMeta;        // 800: xyz=各轴格数;w 保留
    uint  voxData[];      // 816..: 2bit/体素,idx=x+y*dx+z*dx*dy,word=idx>>4,bit=(idx&15)*2
};

// ---- 各阶段矩阵约定(doc06 §2.2 表)----
// 2026-09-02 更正:gbufferModelView = R·T **含 bob 平移**(bobView 写进渲染
// PoseStack,Iris 原样捕获;旧注释"R-only"是错误前提,曾致 mat3-only 换算把
// ±bob 位移注入世界/视图坐标=影子随步频跳位,见坑57)。方向向量用 mat3 仍正确
// (平移对方向无意义,且 bob 微转两侧一致)。

// ----------------------------------------------------------------------------
// 遮挡系数注入点(interop 适配接口):
//   语义:返回该屏幕位置的"遮挡者材质消光系数"∈(0,1],1.0=全挡实心。
//   本包实现(colortex3.a 分类)在 taclight_adapter.glsl;未适配的包回退
//   保守默认 1.0 —— 植被/树叶按实心处理,宁可误挡不可漏光。
// ----------------------------------------------------------------------------
float taclight_occlusion_at(vec2 uv) { return 1.0; }

// ----------------------------------------------------------------------------
// 项目仅有的一对纵深入口(doc06 §2.2 规则 6):
// 全部光照代码只允许调用这两个函数做坐标换算,禁止内联重复公式。
// ----------------------------------------------------------------------------

/** world → 场景相对(world − cameraPosition)。v0.9.0 起上传侧为 world,先走这一步。 */
vec3 taclight_world_to_scene(vec3 worldPos) {
    return worldPos - cameraPosition;
}

/** 场景相对 → 视图空间。**必须用全矩阵**:gbufferModelView = R·T 含 bob 平移
 *  (bobView 写进渲染 PoseStack,Iris 原样捕获),光栅化几何的 fragView 带同一平移;
 *  两侧都含平移,相减才抵消。旧 mat3-only 形式丢平移 → 距离/锥角以步频抖动。 */
vec3 taclight_scene_to_view(vec3 scenePos) {
    return (gbufferModelView * vec4(scenePos, 1.0)).xyz;
}

/** 视图空间 → 屏幕 uv(供遮挡步进等屏幕空间运算取参考 uv)。 */
vec2 taclight_view_to_uv(vec3 viewPos) {
    vec4 clip = gbufferProjection * vec4(viewPos, 1.0);
    return (clip.xy / clip.w) * 0.5 + 0.5;
}

/** 深度缓冲反投影:uv + 设备深度 → 视图空间表面位置(composite 光照的地基)。 */
vec3 taclight_depth_to_view(vec2 uv, float depth) {
    vec4 ndc  = vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec4 view = gbufferProjectionInverse * ndc;
    return view.xyz / view.w;
}

/** 视图空间 → world。**必须用全矩阵逆**:旧 transpose(mat3) 形式丢掉 gbufferModelView
 *  里的 bob 平移 → 反算世界坐标带 ±bob 位移假偏移,体素 DDA 阴影随步频相对画面跳位
 *  (实机差分 7.2-7.4px@bob开 vs 2.3-2.7px@bob关,evidence/2026-09-02-bob-sway-verdict/)。
 *  体素栅格/SSBO 均为 world 域,消费端经本函数出入,不内联换算(铁律 3)。 */
vec3 taclight_view_to_world(vec3 viewPos) {
    return cameraPosition + (gbufferModelViewInverse * vec4(viewPos, 1.0)).xyz;
}

// ----------------------------------------------------------------------------
// 距离衰减(doc06 §2.3 定义 + §5 D6 已批:平滑反平方,k 由半径标定)
//   atten(0)=1,atten(radius)=0,中段长尾自然;分母 1+k·d² 无奇点。
// v0.9.0 M0 热修:绿锥预览即用本函数(替换旧 (1-d/r) 线性淡出——线性在
// 半半径处只剩 50% 亮度,视觉半径"提前死亡",即实测"照明距离不足"主因)。
// M1 表面照明直接复用本函数,不再另写。
// ----------------------------------------------------------------------------
const float TACLIGHT_ATTEN_K = 2.0; // 标定常数:越小尾越长;2.0 = 0.8r 处约 16% 亮度
float taclight_attenuation(float dist, float radius) {
    float k = TACLIGHT_ATTEN_K / max(radius * radius, 1e-4);
    float tail = 1.0 / (1.0 + k * radius * radius);   // = 1/(1+K)
    float e = 1.0 / (1.0 + k * dist * dist) - tail;
    return max(e, 0.0) / (1.0 - tail);
}

// ----------------------------------------------------------------------------
// 近场软肩压缩(v0.9.0 M0 热修,实机反馈:近场过曝糊死、看不清被照物体)
//   f(x) = x / (1 + G·x) —— 斜率设计:
//   · x 小(远场尾部)时 f ≈ x,几乎不衰减(远处保持可见);
//   · x 大(近场)被压向上限 1/G(近场显著压暗,不再削顶);
//   · G = TACLIGHT_KNEE_GAIN 是用户可调的"亮度曲线"旋钮:越大近场压得越狠、
//     近远对比越大;调小则整体趋平(退化为旧行为 G→0)。
// 效果标定(K=2, r=56):0.25r 处 0.83→0.31,0.5r 处 0.50→0.25,
// 0.8r 处 0.158→0.120(×合成增益 1.8 = 0.216 仍清晰可见);近远比 5.3:1 → 2.6:1。
// 参考本项目 v0.8.2 软膝(taclight_knee = e/(1+e))的行为语义,公式重写;
// M1 表面高光压缩直接复用本函数,完整 ACES 仍在 M2。
// ----------------------------------------------------------------------------
const float TACLIGHT_KNEE_GAIN = 2.0;
float taclight_soft_knee(float x) {
    return x / (1.0 + TACLIGHT_KNEE_GAIN * x);
}

/** knee 的逐通道版本(表面照明 radiance 是 vec3)。 */
vec3 taclight_soft_knee3(vec3 x) {
    return x / (1.0 + TACLIGHT_KNEE_GAIN * x);
}

// ----------------------------------------------------------------------------
// 多源感知肩部(v0.10.1,08-30 深夜 interfere 判定:双灯同点线性叠加过亮,用户复核)
//   理论定位:渲染方程 L_o = Σ_i f_r·L_i·(n·ω_i) 对多光源是线性叠加(光的超叠加原理),
//   radiance 的求和保持不动;但人眼亮度感知是压缩的(Stevens 幂律,指数 ~0.33),
//   "N 灯同点应只比单灯略亮"属于感知/显示域 —— 在 filmic 管线里由肩部曲线承担
//   (与色调映射 shoulder 同构;此处放在 M1 输出级,锚点 = 单灯名义核心辐射 T,
//   即曝光理论"把名义曝光锚在肩部起点"的做法)。
//   f(x) = x                    (x ≤ T:逐像素恒等 —— 单灯外观零改变)
//        = T + H·tanh((x-T)/H)  (x > T:C1 平滑收敛,H = q·T)
//   性质:N 灯同点 ≤ T+H = (1+q)·T 恒有界(对未知超和簇也鲁棒);等强度双灯
//   (2T)→ ≈(1+q)·T;q=0.15 → ≤1.22×;非重叠双灯互不影响(逐像素压缩,
//   非按灯数归一,远场/第二灯独立光斑不受压)。
// ----------------------------------------------------------------------------
const float TACLIGHT_SHOULDER_T = 0.55;
const float TACLIGHT_SHOULDER_Q = 0.15;
vec3 taclight_shoulder3(vec3 x, float t, float head) {
    vec3 e = max(x - vec3(t), vec3(0.0));
    return min(x, vec3(t)) + head * tanh(e / head);
}

// ----------------------------------------------------------------------------
// M1 · 表面照明数学(全部公开标准公式,自写实现)
// GGX 分布 × Smith 遮蔽 × Schlick 菲涅尔。枪身金属反光用。
// 阶段二:F0 由适配层解码 —— 金属的 F0 = albedo(彩色),介电为常量灰。
// ----------------------------------------------------------------------------
vec3 taclight_ggx(vec3 n, vec3 v, vec3 l, float roughness, vec3 f0) {
    vec3 h = normalize(v + l);
    float ndh = max(dot(n, h), 0.0);
    float ndv = max(dot(n, v), 1e-3);
    float ndl = max(dot(n, l), 0.0);
    float a = roughness * roughness;
    float a2 = a * a;
    float d = a2 / (3.14159265 * pow(ndh * ndh * (a2 - 1.0) + 1.0, 2.0));
    float k = a * 0.5;
    float g = (ndv / (ndv * (1.0 - k) + k)) * (ndl / (ndl * (1.0 - k) + k));
    vec3 f = f0 + (1.0 - f0) * pow(1.0 - max(dot(h, v), 0.0), 5.0);
    return d * g * f;
}

// ----------------------------------------------------------------------------
// M1 · 屏幕空间遮挡(doc06 §2.7:普通 Iris 包的可行上限,写死接受)
// 沿片元→灯的视图空间射线步进 24 步(F5,2026-08-30:16→24,台阶更细),
// 投影回屏幕与 depthtex1(实心几何深度,不含半透明)比较;被挡则按遮挡者
// 材质系数消光。已知局限:视锥外的遮挡者不投影(墙后物体不挡光)。
// IGN 抖动把台阶软化成噪点(风格层颗粒进一步融合)。
// 遮挡系数经 taclight_occlusion_at(uv) 注入 —— 本包 = colortex3.a 分类
// (1.0 实心 / 0.6 树叶 / 0.25 软植被,见 taclight_adapter.glsl):镂空植被
// 按全挡处理会把满草场景的地面消成死黑、只剩草叶亮(实机实锤),半透折中。
// ----------------------------------------------------------------------------
const float TACLIGHT_SSO_SELF_FREE = 0.6; // 贴灯豁免半径:自身体/枪身贴着灯,其阴影半影
                                     // 物理上全弥散,按"可见即照亮"豁免(消脚下暗环)

// F2(2026-08-30):自体胶囊豁免。灯锚在玩家身体上(眼位+固定偏移),身体
// 对"从身体内发出的光"不是硬遮挡物——半影覆盖全身,不存在锐利阴影边。
// 只豁免贴灯球不够:第三人称下腿部距灯 >0.6m,身体仍把锥的下半切出硬边
// (0830 截图"左右不对称"根因 R2)。Java 侧每灯在 cookie 槽写
// (灯→胶囊中心偏移.xyz, 胶囊半径);胶囊竖直半高为常量。w<=0 = 未启用。
const float TACLIGHT_SELF_CAP_HALF = 1.05;

float taclight_sso(vec3 fragView, vec3 lightView, TacLightSpot L) {
    const int STEPS = 24;   // F5:16→24
    vec3 rayVec = lightView - fragView;
    float dither = taclight_ign(gl_FragCoord.xy);
    float blocked = 0.0;
    // 自体胶囊(视图空间):中心 = 灯位 + mat3 旋转后的偏移;轴 = 世界竖直
    // 经 mat3 旋转。每灯一次预计算,步进内只做点积/距离。
    bool capOn = L.cookie.w > 0.0;
    vec3 capC = lightView + mat3(gbufferModelView) * L.cookie.xyz;
    vec3 capAxis = mat3(gbufferModelView) * vec3(0.0, 1.0, 0.0);
    for (int i = 1; i <= STEPS; i++) {
        float t = (float(i) - 0.5 + dither * 0.9) / float(STEPS);
        vec3 sp = fragView + rayVec * t;
        if (distance(sp, lightView) < TACLIGHT_SSO_SELF_FREE) continue;   // 贴灯豁免
        if (capOn) {
            vec3 rel = sp - capC;
            float h = clamp(dot(rel, capAxis), -TACLIGHT_SELF_CAP_HALF, TACLIGHT_SELF_CAP_HALF);
            if (distance(sp, capC + capAxis * h) < L.cookie.w) continue;  // 自体胶囊豁免
        }
        vec2 suv = taclight_view_to_uv(sp);
        if (any(lessThan(suv, vec2(0.0))) || any(greaterThan(suv, vec2(1.0)))) continue;
        // 热修 13:比较必须在视图空间线性距离上做。设备深度 ≈ near/距离,
        // 同样世界距离差的设备深度值随距离二次缩小——设备深度域比较的 bias
        // 在 5m 外比半格厚真遮挡的信号还大,必漏检,与热修 11 删除轮廓
        // 检测是同款陷阱。
        vec3 occView = taclight_depth_to_view(suv, texture(depthtex1, suv).r);
        float sceneDist = -occView.z;
        float rayDist = -sp.z;
        float biasW = 0.06 + 0.04 * t;   // 世界尺度防自表面 acne;远小于任何真遮挡物
        if (rayDist > sceneDist + biasW) {
            // F2 扩展(2026-08-30):遮挡者本体落在自体胶囊内 → 不计。
            // 场景:第三人称相机下,"灯照亮的远墙"与"玩家身体"在屏幕上重叠,
            // 世界空间射线并未穿体,但深度测试拿身体的深度当遮挡者 → 整面
            // 墙的光斑被自己的身体假消光。按遮挡者的世界位置(非采样点的
            // 屏幕投影)做胶囊判定;occView 与 sceneDist 同源,零额外反投影。
            bool selfOcc = false;
            if (capOn) {
                vec3 orel = occView - capC;
                float oh = clamp(dot(orel, capAxis), -TACLIGHT_SELF_CAP_HALF, TACLIGHT_SELF_CAP_HALF);
                selfOcc = distance(occView, capC + capAxis * oh) < L.cookie.w;
            }
            if (!selfOcc) blocked += taclight_occlusion_at(suv);
        }
    }
    // F5 软化(2026-08-30:斜率 1.2 保留,让细遮挡物(栏杆)从"消到不可见"退化为
    // "半消光闪烁带";1.2 保持"全挡趋灭、边缘缓降",把 IGN 噪闪幅度压一半,代价
    // 是极细遮挡物透光略增(与树叶半透折中同一方向)。
    float occ = blocked / float(STEPS);
    return pow(max(1.0 - occ * 1.2, 0.0), 2.0);
}

/** M1 · 体素 DDA 实心格穿透软化带宽(方块,2026-09-02 根因轮):≥带宽 T=0,
 *  掠边按比例放行;取值依据见 taclight_vox_transmit 头注释。 */
const float TACLIGHT_VOX_FUZZ = 0.35;

// ----------------------------------------------------------------------------
// M1 · 体素 DDA 遮挡(v0.12,2026-09-01 深夜④;立项 = 用户实测墙后地面漏光,
// 满足 AGENTS §4 条件项"实机真见漏光才立项")
// 根治 SSO 已知局限(上方注释:视锥外的遮挡者不投影 → 墙后地面漏光):
// 世界空间 Amanatides-Woo 体素步进,1 格 = 1 体素(对齐方块网格,零重采样误差)。
// 分类码与适配层遮挡系数同源(0 空/1 软植被 0.25/2 树叶 0.60/3 实心 1.0),
// 数据由模组每 tick 采样填充上传(SSBO 尾段 voxOrigin/voxMeta/voxData)。
// 返回透射率 T ∈ [0,1]:实心体素一票否决(T=0,硬阴影——DDA 是精确几何,无需
// SSO 的 1.2 斜率软化);树叶/植被按穿越格数透射衰减。栅格无效或光线任一端点
// 在栅格外 → 返回 -1(调用方回退 SSO;覆盖半径不足的远灯退化为旧行为,不假遮挡)。
// 端点格双向豁免:起点格(灯所在空气格)先步进后判定,天然跳过;终点格(被照
// 表面所属方块,沿射线回退 1e-3 定位)步进至即停,不自遮——端点各让一格后,
// 中间任何实心格都是真遮挡。
// 2026-09-02 根因轮两修(实机四臂消融 evidence/2026-09-02-dda-bob-stripe/):
// ① tie 语义:Amanatides-Woo 同一 crossing time 的全部 tied axes 一次推进——
//   旧单轴分轮会访问射线仅擦边、并未穿入的侧邻格(假阴影边界,随 bob 成片翻转);
// ② 穿透软化带 TACLIGHT_VOX_FUZZ:实心格按射线在其内穿透长度放行(≥带宽仍
//   严格 T=0,墙后遮挡基线不变;掠边按比例部分透射)——影子轮廓上硬 0/1 在 bob
//   亚像素采样移动下成片翻转,即"条纹随视角晃动节奏放大"的机制。帧证据:
//   条纹 = 墙柱硬影(SSO 漏光时被糊掉不可见);实机标定 0.08 不够(边缘 |bob|
//   相关仍 0.13),0.20 ≈ bob 视差(1-2.5cm@3-5m)的 4-8×、≈ 20% 条纹周期。
// ③ 0.20→0.35(2026-09-02 体感轮):用户实测"步行条纹放大仍在,跳跃前进(原版
//   bob 振幅离地衰减)即不明显"= 步频 bob 摇晃 × 硬影缘残留闪烁;加宽半影带
//   压掉边缘时间对比度。墙后遮挡不变(穿墙射线穿透 >> 0.35 仍 T=0)。
// ----------------------------------------------------------------------------
float taclight_vox_transmit(vec3 worldA, vec3 worldB) {
    if (voxOrigin.w <= 0.0) return -1.0;
    vec3 a = worldA - voxOrigin.xyz;      // 方块格空间
    vec3 b = worldB - voxOrigin.xyz;
    vec3 dim = vec3(voxMeta.xyz);
    if (any(lessThan(a, vec3(0.0))) || any(greaterThanEqual(a, dim)) ||
        any(lessThan(b, vec3(0.0))) || any(greaterThanEqual(b, dim))) return -1.0;
    vec3 dv = b - a;
    float len = length(dv);
    if (len < 1e-4) return 1.0;
    vec3 dir = dv / len;
    ivec3 cell = ivec3(floor(a));
    ivec3 last = ivec3(floor(b - dir * 1e-3));
    ivec3 istep = ivec3(dir.x > 0.0 ? 1 : (dir.x < 0.0 ? -1 : 0),
                        dir.y > 0.0 ? 1 : (dir.y < 0.0 ? -1 : 0),
                        dir.z > 0.0 ? 1 : (dir.z < 0.0 ? -1 : 0));
    vec3 tDelta = vec3(abs(dir.x) > 1e-9 ? 1.0 / abs(dir.x) : 1e9,
                       abs(dir.y) > 1e-9 ? 1.0 / abs(dir.y) : 1e9,
                       abs(dir.z) > 1e-9 ? 1.0 / abs(dir.z) : 1e9);
    vec3 tMax = vec3(abs(dir.x) > 1e-9 ? (dir.x > 0.0 ? (float(cell.x) + 1.0 - a.x) : (a.x - float(cell.x))) * tDelta.x : 1e9,
                     abs(dir.y) > 1e-9 ? (dir.y > 0.0 ? (float(cell.y) + 1.0 - a.y) : (a.y - float(cell.y))) * tDelta.y : 1e9,
                     abs(dir.z) > 1e-9 ? (dir.z > 0.0 ? (float(cell.z) + 1.0 - a.z) : (a.z - float(cell.z))) * tDelta.z : 1e9);
    float T = 1.0;
    for (int guard = 0; guard < 384; guard++) {
        float tNext = min(tMax.x, min(tMax.y, tMax.z));
        float tieEps = max(1e-6, abs(tNext) * 1e-5);
        bvec3 tied = lessThanEqual(abs(tMax - vec3(tNext)), vec3(tieEps));
        cell += istep * ivec3(tied);
        tMax += tDelta * vec3(tied);
        if (any(lessThan(cell, ivec3(0))) || any(greaterThanEqual(cell, ivec3(dim)))) return T;
        if (all(equal(cell, last))) return T;
        int idx = cell.x + cell.y * int(dim.x) + cell.z * int(dim.x) * int(dim.y);
        uint code = (voxData[idx >> 4] >> uint((idx & 15) * 2)) & 3u;
        if (code == 3u) {
            // 穿透长度软化:tMax 以归一化方向计,单位=沿射线方块数(终点在 t=len);
            // 出格时间-入格时间(钳到 len)即该格内穿透长度;≥带宽仍 T=0。
            float tExit = min(tMax.x, min(tMax.y, tMax.z));
            float penLen = max(0.0, min(tExit, len) - tNext);
            float f = clamp(penLen / TACLIGHT_VOX_FUZZ, 0.0, 1.0);
            if (f >= 1.0) return 0.0;
            T *= 1.0 - f;
        }
        else if (code == 2u) T *= 0.40;      // 树叶:0.6 遮挡/格 → 透射 0.4/格
        else if (code == 1u) T *= 0.75; // 软植被:0.25 遮挡/格
    }
    return T;
}

/** F3(2026-08-30):spec 项能量钳制。GGX 分布项(d)在低 roughness 下峰值可到
 *  10+,× intensity 6 → 镜面尖峰独占 ~2.0 辐射,与 diffuse/bloom/体积多链叠加
 *  推出饱和平台(R1 高频推手)。diffuse 有 albedo 纹理作视觉载体,spec 是无
 *  载体的窄峰——压幅不压形:0.35 保留高光形状,削去能量尖峰。 */
const float TACLIGHT_SPEC_DAMP = 0.35;

// ----------------------------------------------------------------------------
// M0 · K 键调试绿锥(doc06 §2.4 锥判定公式 × §2.10 诊断方式):
// 忽略材质,锥内输出绿色,用于肉眼验证"数据通道 + 锥几何 + 半径"三件事。
// 返回 [0,1] 的锥内系数(内锥全亮、外锥归零、半径外为零)。
// ----------------------------------------------------------------------------
float taclight_debug_green_cone(TacLightSpot L, vec3 fragView) {
    if ((flags & TACLIGHT_FLAG_DEBUG) == 0u) return 0.0;
    if ((flags & TACLIGHT_FLAG_HAS_DATA) == 0u) return 0.0;

    vec3 lightView = taclight_scene_to_view(taclight_world_to_scene(L.posRadius.xyz));
    vec3 toFrag = fragView - lightView;
    float dist = length(toFrag);
    float radius = L.posRadius.w;
    if (dist > radius || radius < 1e-3) return 0.0;

    // 方向只用旋转部分(gbufferModelView 的平移在 composite 阶段无效)
    vec3 dirView = normalize(mat3(gbufferModelView) * normalize(L.dirType.xyz));
    float cosAng = dot(toFrag / max(dist, 1e-4), dirView);
    // 内锥(cosY)全亮,外锥(cosX)全灭:spot = smoothstep(cosOut, cosIn, cosAng)
    float spot = smoothstep(L.cone.x, L.cone.y, cosAng);
    // 软肩压缩:近场压暗、远场几乎不动(实机调优 2026-08-27,详见上方注释)
    return taclight_soft_knee(spot * taclight_attenuation(dist, radius));
}

// ----------------------------------------------------------------------------
// M1 · 表面照明主循环(interop 版接口):
//   输入 = 适配层解码后的物理量(视图空间片元、线性 albedo、视图法线、
//   LabPBR 粗糙度、金属标志、F0);返回该像素的灯辐射 radiance(未标定,
//   量纲标定/肩部由消费侧完成)。锥判定/衰减/遮挡分流为跨包通用逻辑。
//   遮挡分流(2026-09-02 修订,坑58):体素 DDA **无条件先执行**——它是
//   世界空间射线,灯≈相机时依然有效(起点格先步进后判定、终点格回退
//   1e-3,双端豁免,视线即光路),跳过它 = 自灯影子整体丢失。
//   同轴豁免("可见即无遮挡",热修 12)只救屏幕空间 SSO 的退化
//   (灯在相机处 SSO 假消光黑边),且判定必须在**场景域**
//   (world−camera,无 bob):坑 57 修复后 lightView 是真实视图距离,
//   含 bob 平移 ±0.1,自灯锚点(手持 0.44/枪灯 ~0.6)恰在 0.5 格阈值
//   两侧,随步频翻转 = 影子"消失+移动闪烁"(实机回归)。
// ----------------------------------------------------------------------------
vec3 taclight_surface_lighting(vec3 fragView, vec3 albedo, vec3 n,
                               float roughness, float metal, vec3 f0) {
    vec3 radiance = vec3(0.0);
    for (uint i = 0u; i < lightCount && i < 8u; i++) {
        TacLightSpot L = lights[i];
        if (L.dirType.w < 0.5) continue;          // 预留:类型过滤
        vec3 lightScene = taclight_world_to_scene(L.posRadius.xyz);
        vec3 lightView = taclight_scene_to_view(lightScene);
        vec3 toFrag = fragView - lightView;
        float dist = length(toFrag);
        float radius = L.posRadius.w;
        if (dist > radius || radius < 1e-3) continue;   // 廉价门:半径窗口
        vec3 lf = toFrag / max(dist, 1e-4);       // 灯→片元(锥判定轴,与绿锥同式)
        float cosAng = dot(lf, normalize(mat3(gbufferModelView) * normalize(L.dirType.xyz)));
        float spot = smoothstep(L.cone.x, L.cone.y, cosAng);
        if (spot <= 0.001) continue;              // 廉价门:锥外
        // M1 根因热修(2026-08-27):l 此前直接沿用灯→片元方向,同轴光下
        // dot(n,l) 恒负 → ndl 门拒绝全部像素(五轮"无白光"的真正根因);
        // 光照约定必须是 表面→灯。
        vec3 l = -lf;
        float ndl = max(dot(n, l), 0.0);
        if (ndl <= 0.0) continue;                 // 廉价门:背面
        float vis;
        float vt = taclight_vox_transmit(L.posRadius.xyz, taclight_view_to_world(fragView));
        if (vt >= 0.0) {
            vis = vt;
        } else if (dot(lightScene, lightScene) < 0.25) {
            vis = 1.0;    // 同轴+栅格无效:可见即无遮挡(热修 12 原意)
        } else {
            vis = taclight_sso(fragView, lightView, L);
        }
        if (vis <= 0.003) continue;

        vec3 lc = L.colorIntensity.rgb * L.colorIntensity.a;
        float atten = taclight_attenuation(dist, radius);
        vec3 diffuse = albedo * (ndl * (1.0 - metal));
        vec3 spec = taclight_ggx(n, -normalize(fragView), l, roughness, f0) * (ndl * TACLIGHT_SPEC_DAMP);
        radiance += (diffuse + spec) * lc * (spot * atten * vis);
    }
    return radiance;
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
			finalComposite += taclight_surface_lighting(viewPos, gbuffer.albedo, gbuffer.normalL, gbuffer.material.roughness, 0.0, vec3(0.04));

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

