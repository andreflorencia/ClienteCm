package com.example.petscm.ui.ui.home

import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.content.res.Resources
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.os.Bundle
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import android.Manifest
import android.animation.ValueAnimator
import android.os.Handler
import android.view.animation.LinearInterpolator
import com.example.petscliente.Constants
import com.example.petscliente.R
import com.example.petscliente.Remote.GoogleAPI
import com.example.petscliente.callbacks.FirebaseDriverInfoListener
import com.example.petscliente.callbacks.FirebaseFailedListener
import com.example.petscliente.databinding.FragmentHomeBinding
import com.example.petscliente.models.*

import com.firebase.geofire.GeoFire
import com.firebase.geofire.GeoLocation
import com.firebase.geofire.GeoQueryEventListener
import com.google.android.gms.location.*
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.google.firebase.ktx.Firebase
import com.karumi.dexter.Dexter
import com.karumi.dexter.PermissionToken
import com.karumi.dexter.listener.PermissionDeniedResponse
import com.karumi.dexter.listener.PermissionGrantedResponse
import com.karumi.dexter.listener.PermissionRequest
import com.karumi.dexter.listener.single.PermissionListener
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.schedulers.Schedulers
import org.json.JSONObject
import java.io.IOException
import java.util.*
import io.reactivex.rxjava3.core.Observable

class HomeFragment : Fragment(), OnMapReadyCallback, FirebaseDriverInfoListener,
    FirebaseFailedListener {

    private lateinit var mMap: GoogleMap
    private lateinit var mapFragment: SupportMapFragment

    //Location
    private lateinit var locationCallback: LocationCallback
    private lateinit var fusedLocationProviderClient: FusedLocationProviderClient

    //load driver
    var distance = 1.0
    val LIMIT_RANGE = 10.0
    var previousLocation: Location? = null
    var currentLocation: Location? = null

    var firstTime = true

    val compositeDisposable = CompositeDisposable()
    private lateinit var googleApi: GoogleAPI

    //Moving marker
    var polyLineList: MutableList<LatLng>? = null
    var handler: Handler? = null
    var index: Int = 0
    var next: Int = 0
    var v: Float = 0.0f
    var lat: Double = 0.0
    var lng: Double = 0.0
    var start: LatLng? = null
    var end: LatLng? = null
    //Listener
    private lateinit var firebaseDriverInfoListener: FirebaseDriverInfoListener
    private lateinit var firebaseFailedListener: FirebaseFailedListener

    var cityName = ""

    private var _binding: FragmentHomeBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    override fun onStop() {
        compositeDisposable.clear()
        super.onStop()
    }

    override fun onDestroy() {
        fusedLocationProviderClient.removeLocationUpdates(locationCallback)
        super.onDestroy()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val homeViewModel =
            ViewModelProvider(this).get(HomeViewModel::class.java)

        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        val root: View = binding.root

        init()
        // Obtain the SupportMapFragment and get notified when the map is ready to be used.
        mapFragment = childFragmentManager
            .findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)
        return root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap

        //Request Permissions
        Dexter.withContext(context)
            .withPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            .withListener(object : PermissionListener {
                @SuppressLint("MissingPermission")
                override fun onPermissionGranted(permissions: PermissionGrantedResponse?) {
                    mMap.isMyLocationEnabled = true
                    mMap.uiSettings.isMyLocationButtonEnabled = true
                    mMap.setOnMyLocationButtonClickListener() {
                        fusedLocationProviderClient.lastLocation
                            .addOnFailureListener {
                                Toast.makeText(context, "Error", Toast.LENGTH_SHORT).show()
                            }.addOnSuccessListener { location ->
                                val userLatLng = LatLng(location.latitude, location.longitude)
                                mMap.animateCamera(
                                    CameraUpdateFactory.newLatLngZoom(
                                        userLatLng,
                                        10f
                                    )
                                )
                            }
                        true
                    }

                    val locationButton =
                        (mapFragment.view?.findViewById<View>("1".toInt())?.parent as View)
                            .findViewById<View>("2".toInt())
                    val params = locationButton.layoutParams as RelativeLayout.LayoutParams
                    params.addRule(RelativeLayout.ALIGN_TOP, 0)
                    params.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM, RelativeLayout.TRUE)
                    params.bottomMargin = 250
                }

                override fun onPermissionDenied(permissions: PermissionDeniedResponse?) {
                    Snackbar.make(requireView(), permissions!!.permissionName, Snackbar.LENGTH_LONG)
                        .show()
                }

                override fun onPermissionRationaleShouldBeShown(
                    p0: PermissionRequest?,
                    p1: PermissionToken?
                ) {

                }

            }).check()

        mMap.uiSettings.isZoomControlsEnabled = true
        try {
            val success = googleMap.setMapStyle(
                MapStyleOptions.loadRawResourceStyle(
                    requireContext(),
                    R.raw.uber_maps_style
                )
            )
            if (!success) {
                Log.d("Google Map", "error")
            }
        } catch (e: Resources.NotFoundException) {
            e.printStackTrace()
        }
    }

    private fun init() {
        googleApi = RetrofitClient.instance!!.create(GoogleAPI::class.java)

        firebaseDriverInfoListener = this
        firebaseFailedListener = this
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY, 15000
        ).build()

        locationCallback = object : LocationCallback() {
            override fun onLocationAvailability(p0: LocationAvailability) {
                super.onLocationAvailability(p0)
            }

            override fun onLocationResult(location: LocationResult) {
                super.onLocationResult(location)
                val newPos =
                    LatLng(location.lastLocation!!.latitude, location.lastLocation!!.longitude)
                mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(newPos, 18f))


                //if user as changed location, calculate and load driver again
                if (firstTime) {
                    previousLocation = location.lastLocation
                    currentLocation = location.lastLocation

                    firstTime = false
                } else {
                    previousLocation = currentLocation
                    currentLocation = location.lastLocation
                }

                if (previousLocation?.distanceTo(currentLocation!!)!! / 1000 <= LIMIT_RANGE) {
                    loadAvailableDrivers()
                }
            }
        }

        fusedLocationProviderClient =
            LocationServices.getFusedLocationProviderClient(requireContext())
        if (ActivityCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return
        }
        fusedLocationProviderClient.requestLocationUpdates(
            locationRequest, locationCallback,
            Looper.getMainLooper()
        )

        loadAvailableDrivers()
    }

    private fun loadAvailableDrivers() {
        if (ActivityCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Snackbar.make(requireView(), R.string.permission_required, Snackbar.LENGTH_LONG).show()
            return
        }

        fusedLocationProviderClient.lastLocation
            .addOnFailureListener { e ->
                Snackbar.make(requireView(), e.message!!, Snackbar.LENGTH_LONG).show()
            }.addOnSuccessListener { location ->

                //load all drivers in the city
                val geoCoder = Geocoder(requireContext(), Locale.getDefault())
                val addressList: List<Address>?
                try {
                    addressList = geoCoder.getFromLocation( // CHANGE THIS!!
                        location.latitude, location.longitude,
                        1
                    )
                    cityName = addressList!![0].locality

                    //Query
                    val driver_location_ref = FirebaseDatabase.getInstance()
                        .getReference(Constants.DRIVERS_LOCATION_REFERENCE)
                        .child(cityName)
                    val gf = GeoFire(driver_location_ref)
                    val geoQuery = gf.queryAtLocation(
                        GeoLocation(location.latitude, location.longitude),
                        distance
                    )
                    geoQuery.removeAllListeners()

                    geoQuery.addGeoQueryEventListener(object : GeoQueryEventListener {
                        override fun onKeyEntered(key: String?, location: GeoLocation?) {
                            Constants.driversFound.add(DriverGeoModel(key!!, location!!))
                        }

                        override fun onKeyExited(key: String?) {

                        }

                        override fun onKeyMoved(key: String?, location: GeoLocation?) {

                        }

                        override fun onGeoQueryReady() {
                            if (distance <= LIMIT_RANGE) {
                                distance++
                                loadAvailableDrivers()
                            } else {
                                distance = 0.0
                                addDriverMarker()
                            }
                        }

                        override fun onGeoQueryError(error: DatabaseError?) {
                            Snackbar.make(requireView(), error!!.message, Snackbar.LENGTH_LONG)
                                .show()
                        }

                    })

                    driver_location_ref.addChildEventListener(object : ChildEventListener {
                        override fun onChildAdded(
                            snapshot: DataSnapshot,
                            previousChildName: String?
                        ) {
                            //Have new driver
                            val geoQueryModel = snapshot.getValue(GeoQueryModel::class.java)
                            val geoLocation =
                                GeoLocation(geoQueryModel!!.l!![0], geoQueryModel.l!![1])
                            val driverGeoModel = DriverGeoModel(snapshot.key, geoLocation)
                            val newDriverLocation = Location("")
                            newDriverLocation.latitude = geoLocation.latitude
                            newDriverLocation.latitude = geoLocation.longitude
                            val newDistance = location.distanceTo(newDriverLocation) / 1000 // in km
                            if (newDistance <= LIMIT_RANGE) {
                                findDriverByKey(driverGeoModel)
                            }

                        }

                        override fun onChildChanged(
                            snapshot: DataSnapshot,
                            previousChildName: String?
                        ) {

                        }

                        override fun onChildRemoved(snapshot: DataSnapshot) {

                        }

                        override fun onChildMoved(
                            snapshot: DataSnapshot,
                            previousChildName: String?
                        ) {

                        }

                        override fun onCancelled(error: DatabaseError) {
                            Snackbar.make(requireView(), error.message, Snackbar.LENGTH_LONG)
                                .show()
                        }

                    })

                } catch (e: IOException) {
                    Snackbar.make(requireView(), R.string.permission_required, Snackbar.LENGTH_LONG)
                        .show()
                }
            }
    }

    private fun addDriverMarker() {
        if (Constants.driversFound.size > 0) {
            Observable.fromIterable(Constants.driversFound)
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ driverGeoModel: DriverGeoModel? ->
                    findDriverByKey(driverGeoModel)
                },
                    { t: Throwable? ->
                        Snackbar.make(requireView(), t?.message!!, Snackbar.LENGTH_LONG).show()

                    }
                )

        } else {
            Snackbar.make(
                requireView(),
                getString(R.string.drivers_not_found),
                Snackbar.LENGTH_LONG
            ).show()
        }
    }

    private fun findDriverByKey(driverGeoModel: DriverGeoModel?) {
        FirebaseDatabase.getInstance()
            .getReference(Constants.DRIVER_INFO_REFERENCE)
            .child(driverGeoModel?.key!!)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (snapshot.hasChildren()) {
                        driverGeoModel.driverInfoModel =
                            (snapshot.getValue(DriverInfoModel::class.java))
                        firebaseDriverInfoListener.onDriverInfoLoadSuccess(driverGeoModel)
                    } else {
                        firebaseFailedListener.onFirebaseFailed(getString(R.string.key_not_found) + driverGeoModel.key)

                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    firebaseFailedListener.onFirebaseFailed(error.message)
                }

            })

    }

    override fun onDriverInfoLoadSuccess(driverGeoModel: DriverGeoModel?) {
        //if we already have marker with this key, don't set it again
        if (!Constants.markerList.containsKey(driverGeoModel?.key)) {
            Constants.markerList[driverGeoModel!!.key!!] = mMap.addMarker(
                MarkerOptions()
                    .position(
                        LatLng(
                            driverGeoModel.geoLocation!!.latitude,
                            driverGeoModel.geoLocation!!.longitude
                        )
                    )
                    .flat(true)
                    .title(
                        Constants.buildName(
                            driverGeoModel.driverInfoModel!!.firstName!!,
                            driverGeoModel.driverInfoModel!!.lastName!!
                        )
                    )
                    .snippet(driverGeoModel.driverInfoModel!!.phoneNumber)
                    .icon(BitmapDescriptorFactory.fromResource(R.drawable.car))
            )!!
        } else {
            Toast.makeText(requireContext(), "Error", Toast.LENGTH_LONG).show()
        }

        if (!TextUtils.isEmpty(cityName)) {
            val driverLocation = FirebaseDatabase.getInstance()
                .getReference(Constants.DRIVERS_LOCATION_REFERENCE)
                .child(cityName)
                .child(driverGeoModel?.key!!)
            driverLocation.addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (!snapshot.hasChildren()) {
                        if (Constants.markerList.get(driverGeoModel.key) != null) {
                            val marker = Constants.markerList.get(driverGeoModel.key)!!
                            marker.remove()
                            Constants.markerList.remove(driverGeoModel.key)
                            Constants.driversSubscribe.remove(driverGeoModel.key)
                            driverLocation.removeEventListener(this)
                        }
                    } else {
                        val geoQueryModel = snapshot.getValue(GeoQueryModel::class.java)
                        val animationModel = AnimationModel(false, geoQueryModel!!)

                        if (Constants.markerList.get(driverGeoModel.key) != null) {

                            val marker = Constants.markerList.get(driverGeoModel.key)
                            val oldPosition = Constants.driversSubscribe.get(driverGeoModel.key)

                            val from = StringBuilder()
                                .append(oldPosition?.geoQueryModel?.l?.get(0))
                                .append(",")
                                .append(oldPosition?.geoQueryModel?.l?.get(1))
                                .toString()

                            val to = StringBuilder()
                                .append(animationModel.geoQueryModel.l?.get(0))
                                .append(",")
                                .append(animationModel.geoQueryModel.l?.get(1))
                                .toString()

                            moveMarkerAnimation(
                                driverGeoModel.key!!,
                                animationModel,
                                marker,
                                from,
                                to
                            )
                        } else {
                            Constants.driversSubscribe.put(driverGeoModel.key!!, animationModel)
                        }
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Snackbar.make(requireView(), error.message, Snackbar.LENGTH_LONG).show()
                }

            })
        }
    }

    private fun moveMarkerAnimation(
        key: String,
        newData: AnimationModel,
        marker: Marker?,
        from: String,
        to: String
    ) {

        if (!newData.isRun) {

            //Request API
            compositeDisposable.add(googleApi.getDirections(
                "driving",
                "less_driving",
                from, to,
                getString(R.string.api_key)
            )
            !!.subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe { returnResult ->
                    Log.d("API_RETURN", returnResult)
                    try {

                        val jsonObject = JSONObject(returnResult)
                        val jsonArray = jsonObject.getJSONArray("routes")
                        for (i in 0 until jsonArray.length()) {
                            val route = jsonArray.getJSONObject(i)
                            val poly = route.getJSONObject("overview_polyline")
                            val polyLine = poly.getString("points")
                            polyLineList = Constants.decodePoly(polyLine)
                        }

                        //Moving

                        handler = Handler()
                        index = -1
                        next = 1

                        val runnable = object : Runnable  {
                            override fun run() {
                                if (polyLineList?.size != null) {
                                    if (polyLineList?.size!! > 1) {
                                        if (index < polyLineList!!.size - 2) {
                                            index++
                                            next = index + 1
                                            start = polyLineList!![index]
                                            end = polyLineList!![next]
                                        }
                                    }
                                    val valueAnimator = ValueAnimator.ofInt(0,1)
                                    valueAnimator.duration = 3000
                                    valueAnimator.interpolator = LinearInterpolator()
                                    valueAnimator.addUpdateListener { value ->
                                        v = value.animatedFraction
                                        lat = v*end!!.latitude + (1 - v) * start!!.latitude
                                        lng = v*end!!.longitude + (1 - v) * start!!.longitude
                                        val newPos = LatLng(lat,lng)
                                        marker!!.position = newPos
                                        marker.setAnchor(0.5f, 0.5f)
                                        marker.rotation = Constants.getBearing(start!!,newPos)
                                    }
                                    valueAnimator.start()
                                    if (index < polyLineList!!.size - 2) {
                                        handler!!.postDelayed(this,1500)
                                    } else if (index < polyLineList!!.size - 1) {
                                        newData.isRun = false
                                        Constants.driversSubscribe.put(key,newData)
                                    }
                                }
                            }
                        }

                        handler!!.postDelayed(runnable,1500)

                    } catch (e: Exception) {
                        Snackbar.make(requireView(), e.message!!,Snackbar.LENGTH_LONG).show()
                    }
                }
            )
        }
    }

    override fun onFirebaseFailed(message: String) {

    }
}