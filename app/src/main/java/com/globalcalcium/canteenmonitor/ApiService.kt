package com.globalcalcium.canteenmonitor.network

import com.globalcalcium.canteenmonitor.data.Employee
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

data class LoginRequest(val username: String, val password_hash: String)
data class AuthResponse(val token: String, val message: String)

interface ApiService {
    @POST("/api/v1/auth/login")
    suspend fun login(@Body req: LoginRequest): AuthResponse

    @GET("/api/v1/employees/sync")
    suspend fun getEmployees(): List<Employee>
}