package com.github.tommykw.laravelroutejump

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class LaravelRouteJumpTest : BasePlatformTestCase() {

    fun testSettings() {
        val settings = LaravelRouteJumpSettings.getInstance(project)
        
        assertEquals("php artisan", settings.artisanCommand)
        
        settings.artisanCommand = "docker compose exec app php artisan"
        assertEquals("docker compose exec app php artisan", settings.artisanCommand)
        
        settings.artisanCommand = "php artisan"
        assertEquals("php artisan", settings.artisanCommand)
    }

    fun testExtractPathFromUrl() {
        val action = LaravelRouteJumpAction()
        
        assertEquals("/users/123", action.extractPathFromUrlForTest("http://localhost:8000/users/123"))
        assertEquals("/contact", action.extractPathFromUrlForTest("https://example.com/contact"))
        assertEquals("/users/123", action.extractPathFromUrlForTest("http://localhost:8000/users/123?page=2"))
        assertEquals("/about", action.extractPathFromUrlForTest("https://example.com/about#section"))
        
        assertEquals("/users/123", action.extractPathFromUrlForTest("/users/123"))
        assertEquals("contact", action.extractPathFromUrlForTest("contact"))
        assertEquals("/", action.extractPathFromUrlForTest("/"))
    }

    fun testFindMatchingRoute() {
        val action = LaravelRouteJumpAction()
        val jsonOutput = """[{"uri":"about","action":"App\\Http\\Controllers\\HomeController@about"}]"""
        
        val result = action.findMatchingRouteForTest(jsonOutput, "about")
        assertNotNull("Result should not be null for 'about' route", result)
        assertTrue("Result should contain 'about'", result?.contains("about") ?: false)
    }

    fun testBasicUrlExtraction() {
        val action = LaravelRouteJumpAction()
        
        assertEquals("/users/123", action.extractPathFromUrlForTest("http://localhost:8000/users/123"))
        assertEquals("users/123", action.extractPathFromUrlForTest("users/123"))
    }

    fun testUrlWithQueryParameters() {
        val action = LaravelRouteJumpAction()
        
        assertEquals("/contact", action.extractPathFromUrlForTest("http://localhost:8000/contact?name=test&email=test@example.com"))
        assertEquals("/users/123", action.extractPathFromUrlForTest("https://example.com/users/123?page=2&sort=name"))
        assertEquals("/search", action.extractPathFromUrlForTest("http://localhost:8000/search?q=laravel&category=php"))
        
        assertEquals("/about", action.extractPathFromUrlForTest("https://example.com/about?section=team#contact"))
        assertEquals("/products", action.extractPathFromUrlForTest("http://localhost:8000/products?filter=new#top"))
    }

    fun testRouteParameterMatching() {
        val action = LaravelRouteJumpAction()
        
        val jsonWithParams = """[
            {"uri":"users/{id}","action":"App\\\\Http\\\\Controllers\\\\UserController@show"},
            {"uri":"posts/{id}/comments/{comment}","action":"App\\\\Http\\\\Controllers\\\\CommentController@show"}
        ]"""
        
        val result1 = action.findMatchingRouteForTest(jsonWithParams, "users/123")
        assertNotNull("Result should not be null for 'users/123'", result1)
        
        val result2 = action.findMatchingRouteForTest(jsonWithParams, "posts/456/comments/789")
        assertNotNull("Result should not be null for 'posts/456/comments/789'", result2)
        
        val result3 = action.findMatchingRouteForTest(jsonWithParams, "http://localhost:8000/users/123?edit=true")
        assertNotNull("Result should not be null for URL with query params", result3)
    }

    fun testSubdomainUrlExtraction() {
        val action = LaravelRouteJumpAction()

        assertEquals("/users/123", action.extractPathFromUrlForTest("https://api.example.com/users/123"))
        assertEquals("/admin/dashboard", action.extractPathFromUrlForTest("https://admin.example.com/admin/dashboard"))
        assertEquals("/products", action.extractPathFromUrlForTest("http://shop.localhost:8000/products"))
        assertEquals("/", action.extractPathFromUrlForTest("https://subdomain.example.com/"))
        assertEquals("/api/v1/users", action.extractPathFromUrlForTest("https://api.staging.example.com/api/v1/users"))

        assertEquals("/contact", action.extractPathFromUrlForTest("https://api.example.com/contact?name=test"))
        assertEquals("/users/123", action.extractPathFromUrlForTest("https://admin.example.com/users/123#details"))
    }

    fun testConfigurable() {
        val configurable = LaravelRouteJumpConfigurable(project)

        assertEquals("Laravel Route Jump", configurable.displayName)

        val component = configurable.createComponent()
        assertNotNull(component)

        assertFalse(configurable.isModified)
    }
}
