# ImageLoader

simple Imageloading library for android developed in kotlin


Step1: Add it in your root build.gradle at the end of repositories:

allprojects {
		repositories {
			...
			maven { url 'https://jitpack.io' }
		}
	}
  
 Step2:  Add the dependency
 
 dependencies {
	        implementation 'com.github.bhanup212:ImageLoader:1.0'
	}
  
  
  Usage:
  
  ImageLoader.get(context).load("https://homepages.cae.wisc.edu/~ece533/images/boat.png")
      .default(R.drawable.ic_android).into(holder.imageView)
      
      
      
  Screenshot:
  
  ![Images screen ](https://github.com/bhanup212/ImageLoader/blob/master/recyclerview.png)
  
  
  
  check this repo for example:https://github.com/bhanup212/ImageLoader-Example
  
