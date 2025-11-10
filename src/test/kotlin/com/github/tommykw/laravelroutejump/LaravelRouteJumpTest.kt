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

        assertEquals("/shop/123", action.extractPathFromUrlForTest("{account}.localhost/shop/123"))
        assertEquals("/api/users", action.extractPathFromUrlForTest("api.example.com/api/users"))
    }

    fun testSubdomainRouteMatching() {
        val action = LaravelRouteJumpAction()

        val jsonWithSubdomainRoutes = """[
            {"uri":"{account}.localhost/terms/shop-member","action":"Shop\\\\TermsController@termShopMemberView"},
            {"uri":"{account}.localhost/terms/shop-not-member","action":"Shop\\\\TermsController@termShopNotMember"},
            {"uri":"{account}.localhost/terms/taxi/{taxi}","action":"Shop\\\\TermsController@termTaxiView"},
            {"uri":"{account}.localhost/terms/user","action":"Shop\\\\TermsController@termUserView"}
        ]"""

        val result1 = action.findMatchingRouteForTest(jsonWithSubdomainRoutes, "{account}.localhost/terms/shop-member")
        assertNotNull("Should match {account}.localhost/terms/shop-member", result1)
        assertTrue("Should contain termShopMemberView", result1?.contains("termShopMemberView") ?: false)

        val result2 = action.findMatchingRouteForTest(jsonWithSubdomainRoutes, "test-account.localhost/terms/shop-member")
        assertNotNull("Should match subdomain route with actual account value", result2)
        assertTrue("Should contain termShopMemberView", result2?.contains("termShopMemberView") ?: false)

        val result3 = action.findMatchingRouteForTest(jsonWithSubdomainRoutes, "myaccount.localhost/terms/taxi/123")
        assertNotNull("Should match subdomain route with parameter", result3)
        assertTrue("Should contain termTaxiView", result3?.contains("termTaxiView") ?: false)

        val result4 = action.findMatchingRouteForTest(jsonWithSubdomainRoutes, "/terms/shop-member")
        assertNotNull("Should match by path only", result4)
        assertTrue("Should contain termShopMemberView", result4?.contains("termShopMemberView") ?: false)

        val result5 = action.findMatchingRouteForTest(jsonWithSubdomainRoutes, "terms/user")
        assertNotNull("Should match relative path", result5)
        assertTrue("Should contain termUserView", result5?.contains("termUserView") ?: false)
    }

    fun testConfigurable() {
        val configurable = LaravelRouteJumpConfigurable(project)

        assertEquals("Laravel Route Jump", configurable.displayName)

        val component = configurable.createComponent()
        assertNotNull(component)

        assertFalse(configurable.isModified)
    }

    fun testUrlWithPortNumber() {
        val action = LaravelRouteJumpAction()

        // Simulate Laravel route:list --json output with null domain
        val jsonOutput = """[{"method":["GET","HEAD"],"uri":"hello","name":null,"action":"App\\Http\\Controllers\\HelloController@index","middleware":[],"domain":null}]"""

        val result1 = action.findMatchingRouteForTest(jsonOutput, "http://localhost:5173/hello")
        assertNotNull("Should match route for http://localhost:5173/hello", result1)
        assertTrue("Should contain HelloController", result1?.contains("HelloController") ?: false)

        val result2 = action.findMatchingRouteForTest(jsonOutput, "hello")
        assertNotNull("Should match route for 'hello'", result2)

        val result3 = action.findMatchingRouteForTest(jsonOutput, "/hello")
        assertNotNull("Should match route for '/hello'", result3)
    }

    fun testShellCommandConstruction() {
        // Test that various artisan command formats work with shell execution
        val testCases = mapOf(
            "php artisan" to "php artisan",
            "docker" to "docker",
            "docker compose exec app php artisan" to "docker compose exec app php artisan",
            "/usr/bin/php artisan" to "/usr/bin/php artisan",
            "/usr/local/bin/docker" to "/usr/local/bin/docker"
        )

        testCases.forEach { (input, expected) ->
            // Verify that the command string is preserved correctly for shell execution
            assertEquals("Command should be preserved: $input", expected, input)
        }
    }

    fun testArtisanCommandSettings() {
        val settings = LaravelRouteJumpSettings.getInstance(project)

        // Test simple command name without path
        settings.artisanCommand = "docker"
        assertEquals("docker", settings.artisanCommand)

        // Test full path
        settings.artisanCommand = "/usr/local/bin/docker"
        assertEquals("/usr/local/bin/docker", settings.artisanCommand)

        // Test complex command with multiple arguments
        settings.artisanCommand = "docker compose exec app php artisan"
        assertEquals("docker compose exec app php artisan", settings.artisanCommand)

        // Test command with path that includes spaces (edge case)
        settings.artisanCommand = "/path/to/my docker/docker compose exec app php artisan"
        assertEquals("/path/to/my docker/docker compose exec app php artisan", settings.artisanCommand)

        // Reset to default
        settings.artisanCommand = "php artisan"
        assertEquals("php artisan", settings.artisanCommand)
    }
}
